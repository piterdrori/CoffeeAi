"""Stage 2–4 memory API: Memory Context Packet, session summaries, memory CRUD, and an admin-only
knowledge ingestion path. Stage 3 adds deterministic retrieval/ranking; Stage 4 adds Hermes
coordination (intent, plan, conflicts, compression). Device-owned routes authenticate with the
Stage 1 bearer token and are always filtered by ``request.state.device_id`` — device_id is NEVER
taken from the request body.
"""
from __future__ import annotations

import uuid
from typing import Any

from fastapi import APIRouter, BackgroundTasks, Depends, Header, HTTPException, Request
from pydantic import BaseModel, Field

from config import settings
from devices_api import require_device
from hermes import build_context_packet
from memory_store import (
    ALLOWED_STATUSES,
    ALLOWED_TYPES,
    MemoryStore,
    MemoryStoreUnavailable,
    empty_packet,
    estimate_tokens,
    get_memory_store,
    pack_context,
)

router = APIRouter()

# Device-scoped / admin paths that must bypass the legacy shared-key middleware (auth is enforced by
# the route dependencies). These are specific prefixes so they never shadow the legacy
# /v1/memory/prefetch, /v1/memory/sync, /v1/memory (list), or /v1/memory/{id} routes.
MEMORY_DEVICE_SCOPED_PREFIXES = (
    "/v1/memory/context",
    "/v1/memory/sessions",
    "/v1/memory/items",
    "/v1/memory/knowledge",
)

# Stage 3/4 retrieval fan-out caps (broader pool; Hermes plan + ranking select top items).
_MEMORY_CANDIDATE_LIMIT = 100
_KNOWLEDGE_CANDIDATE_LIMIT = 50


def memory_store_dependency() -> MemoryStore:
    return get_memory_store()


def require_admin(x_admin_key: str | None = Header(default=None)) -> str:
    """Knowledge-write authorization. Must be a dedicated server-only key — never a device bearer
    token and never the shared APK key. Disabled (403) when KNOWLEDGE_ADMIN_KEY is unset."""
    configured = settings.KNOWLEDGE_ADMIN_KEY.strip()
    if not configured or x_admin_key != configured:
        raise HTTPException(status_code=403, detail="admin_key_required")
    return "admin"


def _normalize_language(language: str | None) -> str:
    return (language or "en").strip().lower()[:8] or "en"


async def _audit(store: MemoryStore, entry: dict[str, Any]) -> None:
    try:
        await store.write_audit(entry)
    except Exception:  # noqa: BLE001 - audit is best-effort
        pass


# ---------------- Memory Context Packet -------------------------------------------------------

class ModelCapabilities(BaseModel):
    max_context_tokens: int | None = None
    supports_structured_context: bool | None = None


class MemoryContextRequest(BaseModel):
    request_id: str | None = None
    session_id: str = Field(default="", max_length=128)
    query: str = Field(default="", max_length=2000)
    language: str = "en"
    product: str = "generic"
    product_version: str | None = None
    context_token_budget: int = 800
    model_capabilities: ModelCapabilities | None = None


@router.post("/v1/memory/context")
async def memory_context(
    body: MemoryContextRequest,
    background: BackgroundTasks,
    device_id: str = Depends(require_device),
    store: MemoryStore = Depends(memory_store_dependency),
) -> dict[str, Any]:
    request_id = body.request_id or str(uuid.uuid4())
    language = _normalize_language(body.language)
    budget = max(settings.MEMORY_BUDGET_MIN, min(body.context_token_budget, settings.MEMORY_BUDGET_MAX))
    product = (body.product or "").strip()

    try:
        summary = await store.get_summary(device_id, body.session_id) if body.session_id else None
        memories = await store.list_all_approved(device_id, _MEMORY_CANDIDATE_LIMIT)
        knowledge = await store.list_knowledge_for_retrieval(
            product, language, body.product_version, _KNOWLEDGE_CANDIDATE_LIMIT,
        )
    except MemoryStoreUnavailable:
        # Fail safe: a valid empty fallback packet so the Android app is never blocked on memory.
        return empty_packet(request_id, language, budget, fallback=True)

    packet, usage = build_context_packet(
        request_id=request_id,
        language=language,
        budget=budget,
        query=body.query,
        product=product,
        product_version=body.product_version,
        summary=summary,
        memories=memories,
        knowledge_chunks=knowledge,
    )
    # Privacy-safe audit: ids + counts + Hermes metadata — never query text or memory/content.
    background.add_task(_audit, store, {
        "device_id": device_id,
        "request_id": request_id,
        "actor": "device",
        "action": "context",
        "session_id": body.session_id or None,
        "details": {
            "categories_used": usage["categories"],
            "memory_ids": usage["memory_ids"],
            "knowledge_ids": usage["knowledge_ids"],
            "used_token_estimate": usage["used_token_estimate"],
            "query_len": usage["query_len"],
            "retrieval_strategy": usage["retrieval_strategy"],
            "candidate_count": usage["candidate_count"],
            "selected_count": usage["selected_count"],
            "score_range": usage["score_range"],
            "audit_items": usage["audit_items"],
            "hermes_version": usage.get("hermes_version"),
            "intent": usage.get("intent"),
            "intent_confidence": usage.get("intent_confidence"),
            "retrieval_plan": usage.get("retrieval_plan"),
            "conflicts_detected": usage.get("conflicts_detected"),
            "conflicts_resolved": usage.get("conflicts_resolved"),
            "unresolved_conflicts": usage.get("unresolved_conflicts"),
            "resolution_reasons": usage.get("resolution_reasons"),
            "compression_applied": usage.get("compression_applied"),
            "backend_llm_invoked": False,
            "permanent_memory_writes": 0,
        },
    })
    return packet


# ---------------- Session summaries -----------------------------------------------------------

class SummaryUpsertRequest(BaseModel):
    summary: str = Field(min_length=1, max_length=4000)
    covered_until: str | None = None
    version: int | None = Field(default=None, ge=1)
    language: str | None = None


@router.put("/v1/memory/sessions/{session_id}/summary")
async def put_summary(
    session_id: str,
    body: SummaryUpsertRequest,
    background: BackgroundTasks,
    device_id: str = Depends(require_device),
    store: MemoryStore = Depends(memory_store_dependency),
) -> dict[str, Any]:
    if len(session_id) > settings.MEMORY_SESSION_ID_MAX_CHARS:
        raise HTTPException(status_code=400, detail="session_id_too_long")
    data = {
        "summary": body.summary,
        "covered_until": body.covered_until,
        "language": _normalize_language(body.language) if body.language else None,
        "token_estimate": estimate_tokens(body.summary),
    }
    if body.version is not None:
        data["version"] = body.version
    try:
        row = await store.upsert_summary(device_id, session_id, data)
    except MemoryStoreUnavailable:
        raise HTTPException(status_code=503, detail="memory_store_unavailable")
    background.add_task(_audit, store, {
        "device_id": device_id, "request_id": str(uuid.uuid4()), "actor": "device",
        "action": "summary_upsert", "session_id": session_id,
        "details": {"version": row.get("version"), "token_estimate": row.get("token_estimate")},
    })
    return row


@router.get("/v1/memory/sessions/{session_id}/summary")
async def get_summary(
    session_id: str,
    device_id: str = Depends(require_device),
    store: MemoryStore = Depends(memory_store_dependency),
) -> dict[str, Any]:
    try:
        row = await store.get_summary(device_id, session_id)
    except MemoryStoreUnavailable:
        raise HTTPException(status_code=503, detail="memory_store_unavailable")
    if row is None:
        raise HTTPException(status_code=404, detail="summary_not_found")
    return row


@router.delete("/v1/memory/sessions/{session_id}/summary")
async def delete_summary(
    session_id: str,
    background: BackgroundTasks,
    device_id: str = Depends(require_device),
    store: MemoryStore = Depends(memory_store_dependency),
) -> dict[str, Any]:
    # Retention policy: session summaries are derived data, so deletion is PHYSICAL (no tombstone).
    try:
        deleted = await store.delete_summary(device_id, session_id)
    except MemoryStoreUnavailable:
        raise HTTPException(status_code=503, detail="memory_store_unavailable")
    background.add_task(_audit, store, {
        "device_id": device_id, "request_id": str(uuid.uuid4()), "actor": "device",
        "action": "summary_delete", "session_id": session_id, "details": {"deleted": deleted},
    })
    return {"deleted": deleted, "session_id": session_id}


# ---------------- Memory item CRUD ------------------------------------------------------------

class MemoryCreateRequest(BaseModel):
    type: str
    content: str = Field(min_length=1, max_length=4000)
    summary: str | None = Field(default=None, max_length=2000)
    importance: float | None = Field(default=None, ge=0.0, le=1.0)
    confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    source_session_id: str | None = Field(default=None, max_length=128)
    language: str | None = None
    sensitivity: str | None = None
    metadata: dict[str, Any] | None = None


class MemoryPatchRequest(BaseModel):
    content: str | None = Field(default=None, max_length=4000)
    summary: str | None = Field(default=None, max_length=2000)
    status: str | None = None
    importance: float | None = Field(default=None, ge=0.0, le=1.0)
    confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    sensitivity: str | None = None
    metadata: dict[str, Any] | None = None


@router.get("/v1/memory/items")
async def list_memory_items(
    request: Request,
    device_id: str = Depends(require_device),
    store: MemoryStore = Depends(memory_store_dependency),
) -> dict[str, Any]:
    q = request.query_params
    filters: dict[str, Any] = {"limit": min(int(q.get("limit", 100) or 100), 200)}
    for key in ("type", "status", "source_session_id", "updated_since"):
        if q.get(key):
            filters[key] = q.get(key)
    try:
        items = await store.list_memory_items(device_id, filters)
    except MemoryStoreUnavailable:
        raise HTTPException(status_code=503, detail="memory_store_unavailable")
    return {"items": items, "count": len(items)}


@router.post("/v1/memory/items")
async def create_memory_item(
    body: MemoryCreateRequest,
    background: BackgroundTasks,
    device_id: str = Depends(require_device),
    store: MemoryStore = Depends(memory_store_dependency),
) -> dict[str, Any]:
    if body.type not in ALLOWED_TYPES:
        raise HTTPException(status_code=400, detail="invalid_type")
    # Device-created memories are always PROPOSED (only approved memory is eligible for retrieval).
    data = {
        "type": body.type,
        "content": body.content,
        "summary": body.summary,
        "importance": body.importance if body.importance is not None else 0.5,
        "confidence": body.confidence if body.confidence is not None else 1.0,
        "status": "proposed",
        "source": "device",
        "source_session_id": body.source_session_id,
        "language": _normalize_language(body.language) if body.language else None,
        "sensitivity": (body.sensitivity or "normal"),
        "metadata": body.metadata or {},
    }
    try:
        row = await store.create_memory(device_id, data)
    except MemoryStoreUnavailable:
        raise HTTPException(status_code=503, detail="memory_store_unavailable")
    background.add_task(_audit, store, {
        "device_id": device_id, "request_id": str(uuid.uuid4()), "actor": "device",
        "action": "memory_create", "memory_id": row["id"],
        "details": {"type": body.type, "status": "proposed"},
    })
    return row


@router.patch("/v1/memory/items/{memory_id}")
async def patch_memory_item(
    memory_id: str,
    body: MemoryPatchRequest,
    background: BackgroundTasks,
    device_id: str = Depends(require_device),
    store: MemoryStore = Depends(memory_store_dependency),
) -> dict[str, Any]:
    if body.status is not None and body.status not in ALLOWED_STATUSES:
        raise HTTPException(status_code=400, detail="invalid_status")
    patch = body.model_dump(exclude_none=True)
    if not patch:
        raise HTTPException(status_code=400, detail="no_fields")
    try:
        row = await store.update_memory(device_id, memory_id, patch)
    except MemoryStoreUnavailable:
        raise HTTPException(status_code=503, detail="memory_store_unavailable")
    if row is None:
        raise HTTPException(status_code=404, detail="memory_not_found")
    background.add_task(_audit, store, {
        "device_id": device_id, "request_id": str(uuid.uuid4()), "actor": "device",
        "action": "memory_update", "memory_id": memory_id,
        "details": {"fields": sorted(patch.keys())},
    })
    return row


@router.delete("/v1/memory/items/{memory_id}")
async def delete_memory_item(
    memory_id: str,
    background: BackgroundTasks,
    device_id: str = Depends(require_device),
    store: MemoryStore = Depends(memory_store_dependency),
) -> dict[str, Any]:
    try:
        deleted = await store.soft_delete_memory(device_id, memory_id)
    except MemoryStoreUnavailable:
        raise HTTPException(status_code=503, detail="memory_store_unavailable")
    if not deleted:
        raise HTTPException(status_code=404, detail="memory_not_found")
    background.add_task(_audit, store, {
        "device_id": device_id, "request_id": str(uuid.uuid4()), "actor": "device",
        "action": "memory_delete", "memory_id": memory_id, "details": {"logical": True},
    })
    return {"deleted": True, "id": memory_id}


# ---------------- Knowledge ingestion (admin-only) --------------------------------------------
# Live smoke test (requires KNOWLEDGE_ADMIN_KEY env var — never expose publicly):
#   curl -X POST .../v1/memory/knowledge/documents -H "X-Admin-Key: $KNOWLEDGE_ADMIN_KEY" ...
#   curl -X DELETE .../v1/memory/knowledge/documents/{document_id} -H "X-Admin-Key: ..."
# Device bearer tokens cannot create or delete knowledge documents.

class KnowledgeChunkInput(BaseModel):
    chunk_index: int | None = None
    title: str | None = None
    content: str = Field(min_length=1, max_length=8000)
    metadata: dict[str, Any] | None = None


class KnowledgeDocumentRequest(BaseModel):
    product: str = Field(min_length=1, max_length=128)
    title: str = Field(min_length=1, max_length=256)
    version: str = Field(min_length=1, max_length=64)
    locale: str = Field(min_length=2, max_length=8)
    trust_level: str = Field(min_length=1, max_length=32)
    source: str = Field(min_length=1, max_length=256)
    status: str = "active"
    metadata: dict[str, Any] | None = None
    chunks: list[KnowledgeChunkInput] = Field(default_factory=list)


@router.post("/v1/memory/knowledge/documents")
async def create_knowledge_document(
    body: KnowledgeDocumentRequest,
    background: BackgroundTasks,
    _admin: str = Depends(require_admin),
    store: MemoryStore = Depends(memory_store_dependency),
) -> dict[str, Any]:
    doc_data = {
        "product": body.product, "title": body.title, "version": body.version,
        "locale": _normalize_language(body.locale), "trust_level": body.trust_level,
        "source": body.source, "status": body.status, "metadata": body.metadata or {},
    }
    doc: dict[str, Any] | None = None
    try:
        doc = await store.create_document(doc_data)
        chunk_payloads = [c.model_dump(exclude_none=True) for c in body.chunks]
        chunks = await store.add_chunks(doc["id"], chunk_payloads) if body.chunks else []
    except MemoryStoreUnavailable:
        if doc and doc.get("id"):
            try:
                await store.delete_knowledge_document(doc["id"])
            except Exception:  # noqa: BLE001 - cleanup is best-effort; never leak store errors
                pass
        raise HTTPException(status_code=503, detail="memory_store_unavailable")
    background.add_task(_audit, store, {
        "device_id": None, "request_id": str(uuid.uuid4()), "actor": "admin",
        "action": "knowledge_ingest", "memory_id": None,
        "details": {"document_id": doc["id"], "chunk_count": len(chunks), "product": body.product},
    })
    return {"document_id": doc["id"], "chunk_count": len(chunks)}


@router.delete("/v1/memory/knowledge/documents/{document_id}")
async def delete_knowledge_document(
    document_id: str,
    background: BackgroundTasks,
    _admin: str = Depends(require_admin),
    store: MemoryStore = Depends(memory_store_dependency),
) -> dict[str, Any]:
    """Admin-only maintenance delete for test knowledge cleanup."""
    try:
        deleted = await store.delete_knowledge_document(document_id)
    except MemoryStoreUnavailable:
        raise HTTPException(status_code=503, detail="memory_store_unavailable")
    if not deleted:
        raise HTTPException(status_code=404, detail="document_not_found")
    background.add_task(_audit, store, {
        "device_id": None, "request_id": str(uuid.uuid4()), "actor": "admin",
        "action": "knowledge_delete", "memory_id": None,
        "details": {"document_id": document_id},
    })
    return {"deleted": True, "document_id": document_id}
