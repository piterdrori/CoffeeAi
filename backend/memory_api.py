"""Stage 2–5 memory API: Memory Context Packet, session summaries, memory CRUD, knowledge
ingestion, and Stage 5 memory-learning (candidates + approval workflow). Device-owned routes
authenticate with the Stage 1 bearer token and are always filtered by ``request.state.device_id`` —
device_id is NEVER taken from the request body.

Stage 5: proposals are always created as status=proposed. Context retrieval never writes memory.
"""
from __future__ import annotations

import uuid
from typing import Any

from fastapi import APIRouter, BackgroundTasks, Depends, Header, HTTPException, Request
from pydantic import BaseModel, Field

from config import settings
from devices_api import require_device
from hermes import build_context_packet
from memory_learning import (
    LEARNING_VERSION,
    build_memory_proposals,
    propose_consolidation,
    validate_status_transition,
)
from memory_store import (
    ALLOWED_STATUSES,
    ALLOWED_TYPES,
    MemoryStore,
    MemoryStoreUnavailable,
    empty_packet,
    estimate_tokens,
    get_memory_store,
    pack_context,
    utc_now_iso,
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
    "/v1/memory/candidates",
    "/v1/memory/proposals",
    "/v1/memory/consolidate",
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


def _proposal_public(row: dict[str, Any]) -> dict[str, Any]:
    """Compact proposal view for review endpoints (content included for review only)."""
    meta = row.get("metadata") or {}
    return {
        "id": row["id"],
        "type": row.get("type"),
        "content": row.get("content"),
        "status": row.get("status"),
        "confidence": row.get("confidence"),
        "importance": row.get("importance"),
        "sensitivity": row.get("sensitivity"),
        "source": row.get("source"),
        "source_session_id": row.get("source_session_id"),
        "language": row.get("language"),
        "created_at": row.get("created_at"),
        "updated_at": row.get("updated_at"),
        "reviewed_at": row.get("reviewed_at") or meta.get("reviewed_at"),
        "reviewed_by": row.get("reviewed_by") or meta.get("reviewed_by"),
        "rejection_reason": row.get("rejection_reason") or meta.get("rejection_reason"),
        "normalized_subject": row.get("normalized_subject") or meta.get("normalized_subject"),
        "request_id": row.get("request_id") or meta.get("request_id"),
        "source_turn_id": row.get("source_turn_id") or meta.get("source_turn_id"),
        "supersedes_memory_id": row.get("supersedes_memory_id") or meta.get("supersedes_memory_id"),
        "extraction_reason": meta.get("extraction_reason"),
        "consent_state": meta.get("consent_state"),
        "conflict": meta.get("conflict"),
        "learning_version": meta.get("learning_version") or LEARNING_VERSION,
    }


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
    rejection_reason: str | None = Field(default=None, max_length=500)


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
        existing = await store.get_memory(device_id, memory_id)
        if existing is None:
            raise HTTPException(status_code=404, detail="memory_not_found")
        if body.status is not None and body.status != existing.get("status"):
            if not validate_status_transition(existing["status"], body.status):
                raise HTTPException(status_code=409, detail="invalid_status_transition")
            if body.status == "approved":
                row = await store.approve_and_supersede(
                    device_id, memory_id,
                    content=body.content,
                    reviewed_by="device_user",
                )
                if row is None:
                    raise HTTPException(status_code=409, detail="invalid_status_transition")
                background.add_task(_audit, store, {
                    "device_id": device_id, "request_id": str(uuid.uuid4()), "actor": "device_user",
                    "action": "memory_approve", "memory_id": memory_id,
                    "details": {
                        "previous_status": existing["status"],
                        "supersedes_memory_id": row.get("supersedes_memory_id")
                        or (row.get("metadata") or {}).get("supersedes_memory_id"),
                    },
                })
                return row
            if body.status == "rejected":
                patch["reviewed_at"] = utc_now_iso()
                patch["reviewed_by"] = "device_user"
            if body.status == "deleted":
                deleted = await store.soft_delete_memory(device_id, memory_id)
                if not deleted:
                    raise HTTPException(status_code=404, detail="memory_not_found")
                background.add_task(_audit, store, {
                    "device_id": device_id, "request_id": str(uuid.uuid4()), "actor": "device",
                    "action": "memory_delete", "memory_id": memory_id,
                    "details": {"logical": True, "from_status": existing["status"]},
                })
                return {"deleted": True, "id": memory_id, "status": "deleted"}
        row = await store.update_memory(device_id, memory_id, patch)
    except MemoryStoreUnavailable:
        raise HTTPException(status_code=503, detail="memory_store_unavailable")
    if row is None:
        raise HTTPException(status_code=404, detail="memory_not_found")
    action = "memory_update"
    if body.status == "rejected":
        action = "memory_reject"
    elif body.status == "superseded":
        action = "memory_supersede"
    background.add_task(_audit, store, {
        "device_id": device_id, "request_id": str(uuid.uuid4()), "actor": "device",
        "action": action, "memory_id": memory_id,
        "details": {
            "fields": sorted(patch.keys()),
            "rejection_reason": body.rejection_reason,
            "previous_status": existing.get("status"),
        },
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
        existing = await store.get_memory(device_id, memory_id)
        if existing is None:
            raise HTTPException(status_code=404, detail="memory_not_found")
        if not validate_status_transition(existing["status"], "deleted"):
            raise HTTPException(status_code=409, detail="invalid_status_transition")
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


# ---------------- Stage 5: candidate submission + proposal workflow ---------------------------

class ExplicitStatement(BaseModel):
    text: str = Field(min_length=1, max_length=500)
    category_hint: str | None = Field(default=None, max_length=32)


class ConsentState(BaseModel):
    allow_memory_proposals: bool = True
    allow_sensitive_memory: bool = False


class MemoryCandidatesRequest(BaseModel):
    request_id: str = Field(min_length=8, max_length=64)
    session_id: str = Field(default="", max_length=128)
    turn_id: str = Field(default="", max_length=128)
    language: str = "en"
    user_message_summary: str = Field(default="", max_length=1000)
    assistant_response_summary: str = Field(default="", max_length=1000)
    session_summary: str | None = Field(default=None, max_length=2000)
    explicit_user_statements: list[ExplicitStatement] = Field(default_factory=list, max_length=20)
    consent: ConsentState = Field(default_factory=ConsentState)
    # Rejected if present — clients must not send these
    raw_audio: Any | None = None
    audio: Any | None = None
    raw_transcript: str | None = None
    transcript: str | None = None
    device_id: str | None = None


class ProposalActionRequest(BaseModel):
    content: str | None = Field(default=None, max_length=4000)
    rejection_reason: str | None = Field(default=None, max_length=500)


@router.post("/v1/memory/candidates")
async def submit_memory_candidates(
    body: MemoryCandidatesRequest,
    background: BackgroundTasks,
    device_id: str = Depends(require_device),
    store: MemoryStore = Depends(memory_store_dependency),
) -> dict[str, Any]:
    """Accept compact turn/session learning payload; create proposed memories only."""
    if body.device_id:
        raise HTTPException(status_code=400, detail="device_id_not_allowed_in_body")
    if body.raw_audio is not None or body.audio is not None:
        return {
            "request_id": body.request_id,
            "proposals_created": 0,
            "duplicates_skipped": 0,
            "sensitive_skipped": 0,
            "consent_blocked": False,
            "proposal_ids": [],
            "learning_version": LEARNING_VERSION,
            "reasons": ["raw_audio_rejected"],
        }
    if body.raw_transcript or body.transcript:
        raise HTTPException(status_code=400, detail="raw_transcript_not_allowed")

    payload = {
        "request_id": body.request_id,
        "session_id": body.session_id,
        "turn_id": body.turn_id,
        "language": body.language,
        "user_message_summary": body.user_message_summary,
        "assistant_response_summary": body.assistant_response_summary,
        "session_summary": body.session_summary,
        "explicit_user_statements": [s.model_dump() for s in body.explicit_user_statements],
        "consent": body.consent.model_dump(),
    }

    try:
        # Idempotency: replay returns existing proposal ids
        existing_for_request = await store.find_memories_by_request(
            device_id, body.request_id, body.turn_id or None,
        )
        if existing_for_request:
            ids = [r["id"] for r in existing_for_request]
            background.add_task(_audit, store, {
                "device_id": device_id, "request_id": body.request_id, "actor": "device",
                "action": "candidates_idempotent_replay",
                "session_id": body.session_id or None,
                "details": {
                    "turn_id": body.turn_id or None,
                    "proposal_ids": ids,
                    "proposals_created": 0,
                    "learning_version": LEARNING_VERSION,
                },
            })
            return {
                "request_id": body.request_id,
                "proposals_created": 0,
                "duplicates_skipped": 0,
                "sensitive_skipped": 0,
                "consent_blocked": False,
                "proposal_ids": ids,
                "learning_version": LEARNING_VERSION,
                "idempotent_replay": True,
            }

        existing = await store.list_for_novelty(device_id)
        built = build_memory_proposals(payload=payload, existing_memories=existing)
        created_ids: list[str] = []
        for prop in built["proposals"]:
            # Never auto-approve
            assert prop["status"] == "proposed"
            row = await store.create_memory(device_id, prop)
            if row.get("status") != "proposed":
                await store.soft_delete_memory(device_id, row["id"])
                continue
            created_ids.append(row["id"])
    except MemoryStoreUnavailable:
        # Fail soft: local chat must continue; no partial approved state
        return {
            "request_id": body.request_id,
            "proposals_created": 0,
            "duplicates_skipped": 0,
            "sensitive_skipped": 0,
            "consent_blocked": False,
            "proposal_ids": [],
            "learning_version": LEARNING_VERSION,
            "store_unavailable": True,
        }

    background.add_task(_audit, store, {
        "device_id": device_id, "request_id": body.request_id, "actor": "device",
        "action": "candidates_submit",
        "session_id": body.session_id or None,
        "details": {
            "turn_id": body.turn_id or None,
            "proposals_created": len(created_ids),
            "duplicates_skipped": built["duplicates_skipped"],
            "sensitive_skipped": built["sensitive_skipped"],
            "consent_blocked": built["consent_blocked"],
            "reasons": built.get("reasons") or [],
            "proposal_ids": created_ids,
            "learning_version": LEARNING_VERSION,
            "backend_llm_invoked": False,
        },
    })
    return {
        "request_id": body.request_id,
        "proposals_created": len(created_ids),
        "duplicates_skipped": built["duplicates_skipped"],
        "sensitive_skipped": built["sensitive_skipped"],
        "consent_blocked": built["consent_blocked"],
        "proposal_ids": created_ids,
        "learning_version": LEARNING_VERSION,
        "reasons": built.get("reasons") or [],
    }


@router.get("/v1/memory/proposals")
async def list_proposals(
    request: Request,
    device_id: str = Depends(require_device),
    store: MemoryStore = Depends(memory_store_dependency),
) -> dict[str, Any]:
    status = request.query_params.get("status", "proposed")
    try:
        items = await store.list_memory_items(device_id, {
            "status": status,
            "limit": min(int(request.query_params.get("limit", 100) or 100), 200),
        })
    except MemoryStoreUnavailable:
        raise HTTPException(status_code=503, detail="memory_store_unavailable")
    return {"items": [_proposal_public(i) for i in items], "count": len(items)}


@router.get("/v1/memory/proposals/{memory_id}")
async def get_proposal(
    memory_id: str,
    device_id: str = Depends(require_device),
    store: MemoryStore = Depends(memory_store_dependency),
) -> dict[str, Any]:
    try:
        row = await store.get_memory(device_id, memory_id)
    except MemoryStoreUnavailable:
        raise HTTPException(status_code=503, detail="memory_store_unavailable")
    if row is None:
        raise HTTPException(status_code=404, detail="memory_not_found")
    return _proposal_public(row)


@router.post("/v1/memory/proposals/{memory_id}/approve")
async def approve_proposal(
    memory_id: str,
    background: BackgroundTasks,
    body: ProposalActionRequest | None = None,
    device_id: str = Depends(require_device),
    store: MemoryStore = Depends(memory_store_dependency),
) -> dict[str, Any]:
    body = body or ProposalActionRequest()
    try:
        existing = await store.get_memory(device_id, memory_id)
        if existing is None:
            raise HTTPException(status_code=404, detail="memory_not_found")
        if not validate_status_transition(existing["status"], "approved"):
            raise HTTPException(status_code=409, detail="invalid_status_transition")
        row = await store.approve_and_supersede(
            device_id, memory_id, content=body.content, reviewed_by="device_user",
        )
    except MemoryStoreUnavailable:
        raise HTTPException(status_code=503, detail="memory_store_unavailable")
    if row is None:
        raise HTTPException(status_code=409, detail="invalid_status_transition")
    old_id = row.get("supersedes_memory_id") or (row.get("metadata") or {}).get("supersedes_memory_id")
    background.add_task(_audit, store, {
        "device_id": device_id, "request_id": str(uuid.uuid4()), "actor": "device_user",
        "action": "memory_approve", "memory_id": memory_id,
        "details": {
            "supersedes_memory_id": old_id,
            "edited": body.content is not None,
            "correction": bool(old_id),
        },
    })
    return _proposal_public(row)


@router.post("/v1/memory/proposals/{memory_id}/reject")
async def reject_proposal(
    memory_id: str,
    background: BackgroundTasks,
    body: ProposalActionRequest | None = None,
    device_id: str = Depends(require_device),
    store: MemoryStore = Depends(memory_store_dependency),
) -> dict[str, Any]:
    body = body or ProposalActionRequest()
    try:
        existing = await store.get_memory(device_id, memory_id)
        if existing is None:
            raise HTTPException(status_code=404, detail="memory_not_found")
        if not validate_status_transition(existing["status"], "rejected"):
            raise HTTPException(status_code=409, detail="invalid_status_transition")
        row = await store.update_memory(device_id, memory_id, {
            "status": "rejected",
            "reviewed_at": utc_now_iso(),
            "reviewed_by": "device_user",
            "rejection_reason": body.rejection_reason,
        })
    except MemoryStoreUnavailable:
        raise HTTPException(status_code=503, detail="memory_store_unavailable")
    if row is None:
        raise HTTPException(status_code=404, detail="memory_not_found")
    background.add_task(_audit, store, {
        "device_id": device_id, "request_id": str(uuid.uuid4()), "actor": "device_user",
        "action": "memory_reject", "memory_id": memory_id,
        "details": {"rejection_reason": body.rejection_reason},
    })
    return _proposal_public(row)


@router.post("/v1/memory/consolidate")
async def consolidate_memories(
    background: BackgroundTasks,
    device_id: str = Depends(require_device),
    store: MemoryStore = Depends(memory_store_dependency),
) -> dict[str, Any]:
    """Deterministic episodic consolidation → proposed semantic summary (never auto-approved)."""
    try:
        episodes = await store.list_memory_items(device_id, {
            "type": "episodic", "status": "approved", "limit": 200,
        })
        proposal = propose_consolidation(episodes)
        if not proposal:
            return {
                "proposals_created": 0,
                "proposal_ids": [],
                "episodes_preserved": len(episodes),
                "learning_version": LEARNING_VERSION,
            }
        source_ids = list((proposal.get("metadata") or {}).get("consolidation_of") or [])
        row = await store.create_memory(device_id, proposal)
        still = 0
        for sid in source_ids:
            if await store.get_memory(device_id, sid):
                still += 1
    except MemoryStoreUnavailable:
        raise HTTPException(status_code=503, detail="memory_store_unavailable")
    background.add_task(_audit, store, {
        "device_id": device_id, "request_id": str(uuid.uuid4()), "actor": "device",
        "action": "memory_consolidate", "memory_id": row["id"],
        "details": {
            "source_ids": source_ids,
            "episodes_preserved": still,
            "status": "proposed",
            "learning_version": LEARNING_VERSION,
        },
    })
    return {
        "proposals_created": 1,
        "proposal_ids": [row["id"]],
        "episodes_preserved": still,
        "status": row["status"],
        "learning_version": LEARNING_VERSION,
    }


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
