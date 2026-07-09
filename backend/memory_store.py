"""Stage 2: durable memory foundation.

Storage abstraction for device-owned memory, session summaries, global product knowledge, and a
privacy-safe audit log, plus the pure, deterministic Memory Context Packet packer. As with Stage 1,
this module imports only the stdlib, ``httpx``, and ``config`` (never ``chromadb``) so it is unit
testable without credentials.

Ownership rule: every device-owned query MUST be filtered by ``device_id`` in application code. The
FastAPI backend uses the Supabase service-role key which BYPASSES RLS, so these filters are the
mandatory security control. RLS is enabled in the migration only as deny-by-default defense in depth.
"""
from __future__ import annotations

import math
import re
import uuid
from abc import ABC, abstractmethod
from datetime import datetime, timezone
from typing import Any

import httpx

from config import settings
from supabase_rest import (
    SupabaseKeyError,
    build_supabase_rest_headers,
    memory_store_http_error,
)

ALLOWED_TYPES = ("profile", "episodic", "semantic", "procedural", "preference", "safety")
ALLOWED_STATUSES = ("proposed", "approved", "superseded", "rejected", "deleted")
PROFILE_TYPES = ("profile", "preference")
RETRIEVAL_TYPES = ("episodic", "semantic", "procedural")
_CHARS_PER_TOKEN = 4


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def estimate_tokens(text: str | None) -> int:
    """Deterministic ~4-chars/token estimate. No tokenizer dependency, no LLM."""
    if not text:
        return 0
    return max(1, math.ceil(len(text) / _CHARS_PER_TOKEN))


def normalize_text(text: str) -> str:
    return re.sub(r"\s+", " ", text or "").strip().lower()


def resolve_chunk_index(chunk: dict[str, Any], position: int) -> int:
    """Return an integer chunk_index; omit/null values fall back to array position."""
    value = chunk.get("chunk_index")
    if value is None:
        return position
    return int(value)


class MemoryStoreUnavailable(RuntimeError):
    """Raised when the durable store is unreachable so routes can fail safe (503)."""


def pack_context(
    *,
    request_id: str,
    language: str,
    budget: int,
    summary: dict[str, Any] | None,
    profile: list[dict[str, Any]],
    safety: list[dict[str, Any]],
    knowledge: list[dict[str, Any]],
    relevant: list[dict[str, Any]],
) -> tuple[dict[str, Any], dict[str, Any]]:
    """Deterministically assemble a Memory Context Packet within [budget] tokens.

    Priority (highest first): safety rules → recent session summary → procedural/machine knowledge →
    relevant approved memories → optional profile/personalization. Exact/near-identical text is
    de-duplicated. No LLM/embeddings. Returns (packet, usage) where usage carries ids for auditing.
    """
    used = 0
    seen: set[str] = set()
    categories: list[str] = []
    memory_ids: list[str] = []
    knowledge_ids: list[str] = []

    def fits(text: str) -> bool:
        nonlocal used
        norm = normalize_text(text)
        if not norm or norm in seen:
            return False
        est = estimate_tokens(text)
        if used + est > budget:
            return False
        seen.add(norm)
        used += est
        return True

    out_safety: list[dict[str, Any]] = []
    out_summary: str | None = None
    out_knowledge: list[dict[str, Any]] = []
    out_relevant: list[dict[str, Any]] = []
    out_profile: list[dict[str, Any]] = []

    for item in safety:
        if fits(item["content"]):
            out_safety.append({"id": item["id"], "content": item["content"]})
    if out_safety:
        categories.append("safety")
        memory_ids.extend(i["id"] for i in out_safety)

    if summary and summary.get("summary") and fits(summary["summary"]):
        out_summary = summary["summary"]
        categories.append("recent_summary")

    for chunk in knowledge:
        if fits(chunk["content"]):
            out_knowledge.append(
                {"id": chunk["id"], "title": chunk.get("title"), "content": chunk["content"]}
            )
    if out_knowledge:
        categories.append("knowledge")
        knowledge_ids.extend(i["id"] for i in out_knowledge)

    for mem in relevant:
        if fits(mem["content"]):
            out_relevant.append({"id": mem["id"], "type": mem["type"], "content": mem["content"]})
    if out_relevant:
        categories.append("relevant_memories")
        memory_ids.extend(i["id"] for i in out_relevant)

    for mem in profile:
        if fits(mem["content"]):
            out_profile.append({"id": mem["id"], "content": mem["content"]})
    if out_profile:
        categories.append("profile")
        memory_ids.extend(i["id"] for i in out_profile)

    packet = {
        "request_id": request_id,
        "memory_version": 1,
        "intent": "unknown",
        "language": language,
        "user_profile": out_profile,
        "relevant_memories": out_relevant,
        "knowledge_snippets": out_knowledge,
        "recent_summary": out_summary,
        "safety_rules": out_safety,
        "context_token_budget": budget,
        "used_token_estimate": used,
        "categories_used": categories,
        "fallback": False,
    }
    usage = {
        "categories": categories,
        "memory_ids": memory_ids,
        "knowledge_ids": knowledge_ids,
        "used_token_estimate": used,
    }
    return packet, usage


def empty_packet(request_id: str, language: str, budget: int, *, fallback: bool = False) -> dict[str, Any]:
    return {
        "request_id": request_id,
        "memory_version": 2,
        "intent": "unknown",
        "language": language,
        "user_profile": [],
        "relevant_memories": [],
        "knowledge_snippets": [],
        "recent_summary": None,
        "safety_rules": [],
        "context_token_budget": budget,
        "used_token_estimate": 0,
        "categories_used": [],
        "retrieval_strategy": "deterministic-v1",
        "candidate_count": 0,
        "selected_count": 0,
        "fallback": fallback,
    }


class MemoryStore(ABC):
    durable: bool = False

    @abstractmethod
    async def health(self) -> dict[str, bool | str]: ...

    @abstractmethod
    async def get_summary(self, device_id: str, session_id: str) -> dict[str, Any] | None: ...

    @abstractmethod
    async def upsert_summary(self, device_id: str, session_id: str, data: dict[str, Any]) -> dict[str, Any]: ...

    @abstractmethod
    async def delete_summary(self, device_id: str, session_id: str) -> bool: ...

    @abstractmethod
    async def create_memory(self, device_id: str, data: dict[str, Any]) -> dict[str, Any]: ...

    @abstractmethod
    async def get_memory(self, device_id: str, memory_id: str) -> dict[str, Any] | None: ...

    @abstractmethod
    async def update_memory(self, device_id: str, memory_id: str, patch: dict[str, Any]) -> dict[str, Any] | None: ...

    @abstractmethod
    async def soft_delete_memory(self, device_id: str, memory_id: str) -> bool: ...

    @abstractmethod
    async def list_memory_items(self, device_id: str, filters: dict[str, Any]) -> list[dict[str, Any]]: ...

    @abstractmethod
    async def list_approved(self, device_id: str, types: tuple[str, ...], limit: int) -> list[dict[str, Any]]: ...

    @abstractmethod
    async def list_all_approved(self, device_id: str, limit: int) -> list[dict[str, Any]]: ...

    @abstractmethod
    async def create_document(self, data: dict[str, Any]) -> dict[str, Any]: ...

    @abstractmethod
    async def add_chunks(self, document_id: str, chunks: list[dict[str, Any]]) -> list[dict[str, Any]]: ...

    @abstractmethod
    async def list_knowledge_chunks(self, product: str, locale: str, limit: int) -> list[dict[str, Any]]: ...

    @abstractmethod
    async def list_knowledge_for_retrieval(
        self, product: str, locale: str, product_version: str | None, limit: int,
    ) -> list[dict[str, Any]]: ...

    @abstractmethod
    async def delete_knowledge_document(self, document_id: str) -> bool: ...

    @abstractmethod
    async def write_audit(self, entry: dict[str, Any]) -> None: ...


class InMemoryMemoryStore(MemoryStore):
    durable = False

    def __init__(self) -> None:
        self.memories: dict[str, dict[str, Any]] = {}
        self.summaries: dict[tuple[str, str], dict[str, Any]] = {}
        self.documents: dict[str, dict[str, Any]] = {}
        self.chunks: dict[str, dict[str, Any]] = {}
        self.audit: list[dict[str, Any]] = []

    async def health(self) -> dict[str, bool | str]:
        return {"readable": True, "writable": True}

    async def get_summary(self, device_id, session_id):
        row = self.summaries.get((device_id, session_id))
        return dict(row) if row else None

    async def upsert_summary(self, device_id, session_id, data):
        key = (device_id, session_id)
        now = utc_now_iso()
        existing = self.summaries.get(key)
        row = existing or {
            "id": str(uuid.uuid4()),
            "device_id": device_id,
            "session_id": session_id,
            "created_at": now,
            "version": 0,
        }
        row.update({
            "summary": data["summary"],
            "covered_until": data.get("covered_until"),
            "version": data.get("version", row.get("version", 0) + 1),
            "token_estimate": data.get("token_estimate", estimate_tokens(data["summary"])),
            "language": data.get("language"),
            "updated_at": now,
        })
        self.summaries[key] = row
        return dict(row)

    async def delete_summary(self, device_id, session_id):
        return self.summaries.pop((device_id, session_id), None) is not None

    async def create_memory(self, device_id, data):
        now = utc_now_iso()
        row = {
            "id": str(uuid.uuid4()),
            "device_id": device_id,
            "type": data["type"],
            "content": data["content"],
            "summary": data.get("summary"),
            "confidence": data.get("confidence", 1.0),
            "importance": data.get("importance", 0.5),
            "status": data.get("status", "proposed"),
            "source": data.get("source", "device"),
            "source_session_id": data.get("source_session_id"),
            "language": data.get("language"),
            "product_version": data.get("product_version"),
            "sensitivity": data.get("sensitivity", "normal"),
            "created_at": now,
            "updated_at": now,
            "last_used_at": None,
            "deleted_at": None,
            "metadata": data.get("metadata", {}),
        }
        self.memories[row["id"]] = row
        return dict(row)

    async def get_memory(self, device_id, memory_id):
        row = self.memories.get(memory_id)
        if row and row["device_id"] == device_id and row.get("deleted_at") is None:
            return dict(row)
        return None

    async def update_memory(self, device_id, memory_id, patch):
        row = self.memories.get(memory_id)
        if not row or row["device_id"] != device_id or row.get("deleted_at") is not None:
            return None
        for key in ("content", "summary", "status", "confidence", "importance", "sensitivity", "metadata"):
            if key in patch and patch[key] is not None:
                row[key] = patch[key]
        row["updated_at"] = utc_now_iso()
        return dict(row)

    async def soft_delete_memory(self, device_id, memory_id):
        row = self.memories.get(memory_id)
        if not row or row["device_id"] != device_id or row.get("deleted_at") is not None:
            return False
        row["deleted_at"] = utc_now_iso()
        row["status"] = "deleted"
        row["updated_at"] = row["deleted_at"]
        return True

    async def list_memory_items(self, device_id, filters):
        rows = [
            r for r in self.memories.values()
            if r["device_id"] == device_id and r.get("deleted_at") is None
        ]
        if filters.get("type"):
            rows = [r for r in rows if r["type"] == filters["type"]]
        if filters.get("status"):
            rows = [r for r in rows if r["status"] == filters["status"]]
        if filters.get("source_session_id"):
            rows = [r for r in rows if r.get("source_session_id") == filters["source_session_id"]]
        if filters.get("updated_since"):
            rows = [r for r in rows if r["updated_at"] >= filters["updated_since"]]
        rows.sort(key=lambda r: r["updated_at"], reverse=True)
        limit = filters.get("limit", 100)
        return [dict(r) for r in rows[:limit]]

    async def list_approved(self, device_id, types, limit):
        rows = [
            r for r in self.memories.values()
            if r["device_id"] == device_id
            and r.get("deleted_at") is None
            and r["status"] == "approved"
            and r["type"] in types
        ]
        rows.sort(key=lambda r: r["updated_at"], reverse=True)
        return [dict(r) for r in rows[:limit]]

    async def list_all_approved(self, device_id, limit):
        return await self.list_approved(device_id, ALLOWED_TYPES, limit)

    async def create_document(self, data):
        now = utc_now_iso()
        row = {
            "id": str(uuid.uuid4()),
            "product": data["product"],
            "title": data["title"],
            "version": data["version"],
            "locale": data["locale"],
            "trust_level": data["trust_level"],
            "source": data["source"],
            "status": data.get("status", "active"),
            "created_at": now,
            "updated_at": now,
            "metadata": data.get("metadata", {}),
        }
        self.documents[row["id"]] = row
        return dict(row)

    async def add_chunks(self, document_id, chunks):
        out = []
        for i, chunk in enumerate(chunks):
            row = {
                "id": str(uuid.uuid4()),
                "document_id": document_id,
                "chunk_index": resolve_chunk_index(chunk, i),
                "title": chunk.get("title"),
                "content": chunk["content"],
                "token_estimate": chunk.get("token_estimate", estimate_tokens(chunk["content"])),
                "metadata": chunk.get("metadata", {}),
            }
            self.chunks[row["id"]] = row
            out.append(dict(row))
        return out

    async def list_knowledge_chunks(self, product, locale, limit):
        doc_ids = {
            d["id"] for d in self.documents.values()
            if d["status"] == "active" and d["product"] == product and d["locale"] == locale
        }
        rows = [dict(c) for c in self.chunks.values() if c["document_id"] in doc_ids]
        rows.sort(key=lambda c: c["chunk_index"])
        return rows[:limit]

    async def list_knowledge_for_retrieval(self, product, locale, product_version, limit):
        """Return chunks enriched with parent document metadata for Stage 3 ranking."""
        del product_version  # tier logic lives in memory_retrieval.filter_knowledge_candidates
        req_locale = (locale or "en").lower()[:8]
        products = {product}
        if product and product != "generic":
            products.add("coffeeai")
        out: list[dict[str, Any]] = []
        for doc in self.documents.values():
            if doc["status"] != "active":
                continue
            if doc["product"] not in products:
                continue
            doc_locale = (doc.get("locale") or "").lower()
            if doc_locale != req_locale and doc_locale[:2] != req_locale[:2]:
                continue
            for chunk in self.chunks.values():
                if chunk["document_id"] != doc["id"]:
                    continue
                row = dict(chunk)
                row["_document"] = dict(doc)
                out.append(row)
        out.sort(key=lambda c: (c["_document"]["product"], c.get("chunk_index", 0)))
        return out[:limit]

    async def delete_knowledge_document(self, document_id):
        if document_id not in self.documents:
            return False
        chunk_ids = [cid for cid, c in self.chunks.items() if c["document_id"] == document_id]
        for cid in chunk_ids:
            del self.chunks[cid]
        del self.documents[document_id]
        return True

    async def write_audit(self, entry):
        self.audit.append(dict(entry))


class SupabaseMemoryStore(MemoryStore):
    """Durable store backed by Supabase PostgREST using the server-only service-role key."""

    durable = True

    def __init__(self, url: str, service_role_key: str, timeout: float = 5.0) -> None:
        self._base = f"{url.rstrip('/')}/rest/v1"
        self._headers = build_supabase_rest_headers(service_role_key)
        self._timeout = timeout

    def _client(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(timeout=self._timeout, headers=self._headers)

    async def _get(self, table: str, params: dict[str, Any]) -> list[dict[str, Any]]:
        try:
            async with self._client() as client:
                resp = await client.get(f"{self._base}/{table}", params=params)
                resp.raise_for_status()
                return resp.json()
        except httpx.HTTPError as exc:
            memory_store_http_error(f"get:{table}", exc)
            raise MemoryStoreUnavailable(str(exc)) from exc

    async def _insert(self, table: str, payload: dict[str, Any]) -> dict[str, Any]:
        try:
            async with self._client() as client:
                resp = await client.post(
                    f"{self._base}/{table}",
                    params={"select": "*"},
                    headers={**self._headers, "Prefer": "return=representation"},
                    json=payload,
                )
                resp.raise_for_status()
                rows = resp.json()
        except httpx.HTTPError as exc:
            memory_store_http_error(f"insert:{table}", exc)
            raise MemoryStoreUnavailable(str(exc)) from exc
        return rows[0] if isinstance(rows, list) and rows else payload

    async def _patch(self, table: str, filt: dict[str, Any], payload: dict[str, Any]) -> list[dict[str, Any]]:
        try:
            async with self._client() as client:
                resp = await client.patch(
                    f"{self._base}/{table}",
                    params={**filt, "select": "*"},
                    headers={**self._headers, "Prefer": "return=representation"},
                    json=payload,
                )
                resp.raise_for_status()
                return resp.json()
        except httpx.HTTPError as exc:
            memory_store_http_error(f"patch:{table}", exc)
            raise MemoryStoreUnavailable(str(exc)) from exc

    async def health(self) -> dict[str, bool | str]:
        readable = False
        try:
            async with self._client() as client:
                resp = await client.get(f"{self._base}/memory_items", params={"select": "id", "limit": 1})
                readable = resp.status_code == 200
        except httpx.HTTPError:
            return {"readable": False, "writable": "unknown"}
        return {"readable": readable, "writable": "unknown"}

    async def get_summary(self, device_id, session_id):
        rows = await self._get("conversation_summaries", {
            "device_id": f"eq.{device_id}", "session_id": f"eq.{session_id}", "select": "*", "limit": 1,
        })
        return rows[0] if rows else None

    async def upsert_summary(self, device_id, session_id, data):
        existing = await self.get_summary(device_id, session_id)
        payload = {
            "summary": data["summary"],
            "covered_until": data.get("covered_until"),
            "token_estimate": data.get("token_estimate", estimate_tokens(data["summary"])),
            "language": data.get("language"),
            "updated_at": utc_now_iso(),
        }
        if existing is None:
            payload.update({
                "device_id": device_id, "session_id": session_id,
                "version": data.get("version", 1),
            })
            return await self._insert("conversation_summaries", payload)
        payload["version"] = data.get("version", int(existing.get("version", 1)) + 1)
        rows = await self._patch("conversation_summaries", {
            "device_id": f"eq.{device_id}", "session_id": f"eq.{session_id}",
        }, payload)
        return rows[0] if rows else payload

    async def delete_summary(self, device_id, session_id):
        try:
            async with self._client() as client:
                resp = await client.delete(f"{self._base}/conversation_summaries", params={
                    "device_id": f"eq.{device_id}", "session_id": f"eq.{session_id}",
                })
                resp.raise_for_status()
            return True
        except httpx.HTTPError as exc:
            memory_store_http_error("delete:conversation_summaries", exc)
            raise MemoryStoreUnavailable(str(exc)) from exc

    async def create_memory(self, device_id, data):
        payload = {**data, "device_id": device_id}
        return await self._insert("memory_items", payload)

    async def get_memory(self, device_id, memory_id):
        rows = await self._get("memory_items", {
            "id": f"eq.{memory_id}", "device_id": f"eq.{device_id}",
            "deleted_at": "is.null", "select": "*", "limit": 1,
        })
        return rows[0] if rows else None

    async def update_memory(self, device_id, memory_id, patch):
        clean = {k: v for k, v in patch.items()
                 if k in ("content", "summary", "status", "confidence", "importance", "sensitivity", "metadata")
                 and v is not None}
        clean["updated_at"] = utc_now_iso()
        rows = await self._patch("memory_items", {
            "id": f"eq.{memory_id}", "device_id": f"eq.{device_id}", "deleted_at": "is.null",
        }, clean)
        return rows[0] if rows else None

    async def soft_delete_memory(self, device_id, memory_id):
        now = utc_now_iso()
        rows = await self._patch("memory_items", {
            "id": f"eq.{memory_id}", "device_id": f"eq.{device_id}", "deleted_at": "is.null",
        }, {"deleted_at": now, "status": "deleted", "updated_at": now})
        return bool(rows)

    async def list_memory_items(self, device_id, filters):
        params: dict[str, Any] = {
            "device_id": f"eq.{device_id}", "deleted_at": "is.null", "select": "*",
            "order": "updated_at.desc", "limit": filters.get("limit", 100),
        }
        if filters.get("type"):
            params["type"] = f"eq.{filters['type']}"
        if filters.get("status"):
            params["status"] = f"eq.{filters['status']}"
        if filters.get("source_session_id"):
            params["source_session_id"] = f"eq.{filters['source_session_id']}"
        if filters.get("updated_since"):
            params["updated_at"] = f"gte.{filters['updated_since']}"
        return await self._get("memory_items", params)

    async def list_approved(self, device_id, types, limit):
        type_filter = ",".join(types)
        return await self._get("memory_items", {
            "device_id": f"eq.{device_id}", "deleted_at": "is.null", "status": "eq.approved",
            "type": f"in.({type_filter})", "select": "*", "order": "updated_at.desc", "limit": limit,
        })

    async def list_all_approved(self, device_id, limit):
        type_filter = ",".join(ALLOWED_TYPES)
        return await self._get("memory_items", {
            "device_id": f"eq.{device_id}", "deleted_at": "is.null", "status": "eq.approved",
            "type": f"in.({type_filter})", "select": "*", "order": "updated_at.desc", "limit": limit,
        })

    async def create_document(self, data):
        return await self._insert("knowledge_documents", data)

    async def add_chunks(self, document_id, chunks):
        payload = [{
            "document_id": document_id,
            "chunk_index": resolve_chunk_index(c, i),
            "title": c.get("title"),
            "content": c["content"],
            "token_estimate": c.get("token_estimate", estimate_tokens(c["content"])),
            "metadata": c.get("metadata", {}),
        } for i, c in enumerate(chunks)]
        try:
            async with self._client() as client:
                resp = await client.post(
                    f"{self._base}/knowledge_chunks",
                    params={"select": "*"},
                    headers={**self._headers, "Prefer": "return=representation"},
                    json=payload,
                )
                resp.raise_for_status()
                return resp.json()
        except httpx.HTTPError as exc:
            memory_store_http_error("insert:knowledge_chunks", exc)
            raise MemoryStoreUnavailable(str(exc)) from exc

    async def list_knowledge_chunks(self, product, locale, limit):
        docs = await self._get("knowledge_documents", {
            "product": f"eq.{product}", "locale": f"eq.{locale}", "status": "eq.active", "select": "id",
        })
        if not docs:
            return []
        ids = ",".join(d["id"] for d in docs)
        return await self._get("knowledge_chunks", {
            "document_id": f"in.({ids})", "select": "*", "order": "chunk_index.asc", "limit": limit,
        })

    async def list_knowledge_for_retrieval(self, product, locale, product_version, limit):
        del product_version
        if not product or product == "generic":
            return []
        req_locale = (locale or "en").lower()[:8]
        docs = await self._get("knowledge_documents", {
            "status": "eq.active",
            "product": f"in.({product},coffeeai)",
            "select": "id,product,title,version,locale,trust_level,source,status,metadata,created_at,updated_at",
            "limit": 200,
        })
        eligible = [
            d for d in docs
            if (d.get("locale") or "").lower() == req_locale
            or (d.get("locale") or "").lower()[:2] == req_locale[:2]
        ]
        if not eligible:
            return []
        ids = ",".join(d["id"] for d in eligible)
        chunks = await self._get("knowledge_chunks", {
            "document_id": f"in.({ids})", "select": "*", "order": "chunk_index.asc", "limit": limit,
        })
        doc_by_id = {d["id"]: d for d in eligible}
        out: list[dict[str, Any]] = []
        for chunk in chunks:
            doc = doc_by_id.get(chunk["document_id"])
            if not doc:
                continue
            row = dict(chunk)
            row["_document"] = doc
            out.append(row)
        return out

    async def delete_knowledge_document(self, document_id):
        try:
            async with self._client() as client:
                await client.delete(f"{self._base}/knowledge_chunks", params={
                    "document_id": f"eq.{document_id}",
                })
                resp = await client.delete(f"{self._base}/knowledge_documents", params={
                    "id": f"eq.{document_id}",
                })
                resp.raise_for_status()
            return True
        except httpx.HTTPError as exc:
            memory_store_http_error("delete:knowledge_documents", exc)
            raise MemoryStoreUnavailable(str(exc)) from exc

    async def write_audit(self, entry):
        try:
            await self._insert("memory_audit_log", entry)
        except MemoryStoreUnavailable:
            pass  # audit is best-effort; never fail the request on audit write


_store: MemoryStore | None = None


def get_memory_store() -> MemoryStore:
    global _store
    if _store is None:
        if settings.supabase_enabled:
            try:
                _store = SupabaseMemoryStore(settings.SUPABASE_URL, settings.SUPABASE_SERVICE_ROLE_KEY)
            except SupabaseKeyError as exc:
                raise RuntimeError("invalid_supabase_server_key_configuration") from exc
        else:
            _store = InMemoryMemoryStore()
    return _store
