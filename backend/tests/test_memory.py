"""Stage 2 memory API tests. No Supabase/credentials — in-memory device + memory stores are
injected via dependency overrides; devices are registered through the real Stage 1 flow to get
bearer tokens, so device isolation and auth are exercised realistically."""
from __future__ import annotations

import json

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import devices_api
import memory_api
from config import settings
from devices import InMemoryDeviceStore, RateLimiter
from memory_store import InMemoryMemoryStore, MemoryStoreUnavailable, estimate_tokens, pack_context


def build():
    app = FastAPI()
    app.include_router(devices_api.router)
    app.include_router(memory_api.router)
    dev_store = InMemoryDeviceStore()
    mem_store = InMemoryMemoryStore()
    app.dependency_overrides[devices_api.store_dependency] = lambda: dev_store
    app.dependency_overrides[devices_api.limiter_dependency] = lambda: RateLimiter(1000, 600)
    app.dependency_overrides[memory_api.memory_store_dependency] = lambda: mem_store
    client = TestClient(app)
    return app, client, mem_store


def register(client, install_id):
    r = client.post("/v1/devices/register", json={"install_id": install_id})
    assert r.status_code == 200
    body = r.json()
    return body["device_id"], {"Authorization": f"Bearer {body['device_token']}"}


def make_memory(client, hdr, mtype, content, approve=True, **extra):
    r = client.post("/v1/memory/items", json={"type": mtype, "content": content, **extra}, headers=hdr)
    assert r.status_code == 200, r.text
    mid = r.json()["id"]
    assert r.json()["status"] == "proposed"  # device-created memory is always proposed
    if approve:
        pr = client.patch(f"/v1/memory/items/{mid}", json={"status": "approved"}, headers=hdr)
        assert pr.status_code == 200
    return mid


def context(client, hdr, **kw):
    payload = {"session_id": "s1", "query": "how do I clean it", "language": "en", "context_token_budget": 800}
    payload.update(kw)
    return client.post("/v1/memory/context", json=payload, headers=hdr)


# 1 — authenticated empty context packet
def test_empty_context_packet():
    _, client, _ = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    resp = context(client, hdr)
    assert resp.status_code == 200
    body = resp.json()
    assert body["user_profile"] == [] and body["relevant_memories"] == []
    assert body["safety_rules"] == [] and body["recent_summary"] is None
    assert body["fallback"] is False
    assert body["used_token_estimate"] == 0


# 2 — missing / invalid bearer rejected
def test_context_requires_bearer():
    _, client, _ = build()
    assert context(client, {}).status_code == 401
    assert context(client, {"Authorization": "Bearer nope"}).status_code == 401


# 3 — device isolation (A's approved memory invisible to B)
def test_device_isolation():
    _, client, _ = build()
    _, a = register(client, "install-aaaaaaaaaaaaaaaa")
    _, b = register(client, "install-bbbbbbbbbbbbbbbb")
    make_memory(client, a, "semantic", "A likes oat milk")
    a_ctx = context(client, a).json()
    b_ctx = context(client, b).json()
    assert any("oat milk" in m["content"] for m in a_ctx["relevant_memories"])
    assert b_ctx["relevant_memories"] == []


# 4 / 5 — approved returned, proposed excluded
def test_approved_returned_proposed_excluded():
    _, client, _ = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    make_memory(client, hdr, "semantic", "approved fact", approve=True)
    make_memory(client, hdr, "semantic", "proposed fact", approve=False)
    rel = context(client, hdr).json()["relevant_memories"]
    contents = [m["content"] for m in rel]
    assert "approved fact" in contents
    assert "proposed fact" not in contents


# 6 — superseded/rejected/deleted excluded
def test_non_approved_statuses_excluded():
    _, client, _ = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    mid = make_memory(client, hdr, "semantic", "temporary fact", approve=True)
    client.patch(f"/v1/memory/items/{mid}", json={"status": "superseded"}, headers=hdr)
    assert context(client, hdr).json()["relevant_memories"] == []


# 7 — session summary returned
def test_session_summary_in_context():
    _, client, _ = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    client.put("/v1/memory/sessions/s1/summary", json={"summary": "User asked about descaling."}, headers=hdr)
    body = context(client, hdr, session_id="s1").json()
    assert body["recent_summary"] == "User asked about descaling."


# 8 — knowledge chunk by product/language
def test_knowledge_chunk_returned(monkeypatch):
    monkeypatch.setattr(settings, "KNOWLEDGE_ADMIN_KEY", "admin-secret")
    _, client, _ = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    doc = client.post(
        "/v1/memory/knowledge/documents",
        headers={"X-Admin-Key": "admin-secret"},
        json={
            "product": "coffee-x", "title": "Cleaning", "version": "1", "locale": "en",
            "trust_level": "verified", "source": "test",
            "chunks": [{"content": "Rinse the brew group weekly."}],
        },
    )
    assert doc.status_code == 200
    body = context(client, hdr, product="coffee-x").json()
    assert any("Rinse the brew group" in c["content"] for c in body["knowledge_snippets"])


# 16 — knowledge write path rejects device tokens
def test_knowledge_write_rejects_device_token(monkeypatch):
    monkeypatch.setattr(settings, "KNOWLEDGE_ADMIN_KEY", "admin-secret")
    _, client, _ = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    resp = client.post(
        "/v1/memory/knowledge/documents",
        headers=hdr,  # device bearer, no X-Admin-Key
        json={"product": "coffee-x", "title": "t", "version": "1", "locale": "en",
              "trust_level": "verified", "source": "test", "chunks": []},
    )
    assert resp.status_code == 403


# 9 / 10 / 11 — token budget, safety priority, dedup (pure packer)
def test_pack_context_budget_priority_dedup():
    safety = [{"id": "s", "content": "Never immerse the machine in water."}]
    summary = {"summary": "Prior chat about milk."}
    knowledge = [{"id": "k", "title": None, "content": "Descale every two months."}]
    relevant = [
        {"id": "r1", "type": "semantic", "content": "User likes oat milk."},
        {"id": "r2", "type": "semantic", "content": "User likes oat milk."},  # exact dup
    ]
    profile = [{"id": "p", "content": "Name is Sam."}]
    # Budget only large enough for safety + summary.
    budget = estimate_tokens(safety[0]["content"]) + estimate_tokens(summary["summary"])
    packet, usage = pack_context(
        request_id="req", language="en", budget=budget,
        summary=summary, profile=profile, safety=safety, knowledge=knowledge, relevant=relevant,
    )
    assert packet["safety_rules"] and packet["recent_summary"] == "Prior chat about milk."
    assert packet["knowledge_snippets"] == []  # budget exhausted after higher-priority content
    assert packet["relevant_memories"] == [] and packet["user_profile"] == []
    assert packet["used_token_estimate"] <= budget

    # With a big budget, dedup keeps only one copy of the duplicated memory.
    packet2, _ = pack_context(
        request_id="req", language="en", budget=100000,
        summary=summary, profile=profile, safety=safety, knowledge=knowledge, relevant=relevant,
    )
    assert len(packet2["relevant_memories"]) == 1


# 12 / 13 — audit record created; no full query/content logged
def test_audit_created_without_query_or_content():
    _, client, mem_store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    make_memory(client, hdr, "semantic", "SENSITIVE-MEMORY-CONTENT")
    secret_query = "SUPER-SECRET-QUERY-TEXT"
    context(client, hdr, query=secret_query)
    ctx_events = [e for e in mem_store.audit if e["action"] == "context"]
    assert ctx_events
    blob = json.dumps(mem_store.audit)
    assert secret_query not in blob
    assert "SENSITIVE-MEMORY-CONTENT" not in blob  # ids only, never content
    assert ctx_events[-1]["details"]["query_len"] == len(secret_query)


# 14 — memory CRUD is device-scoped
def test_memory_crud_device_scoped():
    _, client, _ = build()
    _, a = register(client, "install-aaaaaaaaaaaaaaaa")
    _, b = register(client, "install-bbbbbbbbbbbbbbbb")
    mid = make_memory(client, a, "semantic", "A only", approve=False)
    assert client.get("/v1/memory/items", headers=b).json()["items"] == []
    assert client.patch(f"/v1/memory/items/{mid}", json={"content": "hijack"}, headers=b).status_code == 404
    assert client.delete(f"/v1/memory/items/{mid}", headers=b).status_code == 404
    assert client.delete(f"/v1/memory/items/{mid}", headers=a).status_code == 200


# 15 — summary CRUD is device-scoped
def test_summary_crud_device_scoped():
    _, client, _ = build()
    _, a = register(client, "install-aaaaaaaaaaaaaaaa")
    _, b = register(client, "install-bbbbbbbbbbbbbbbb")
    client.put("/v1/memory/sessions/s1/summary", json={"summary": "A summary"}, headers=a)
    assert client.get("/v1/memory/sessions/s1/summary", headers=b).status_code == 404
    assert client.get("/v1/memory/sessions/s1/summary", headers=a).status_code == 200


# 17 — database unavailable fails safely
def test_database_unavailable_fails_safe():
    app = FastAPI()
    app.include_router(devices_api.router)
    app.include_router(memory_api.router)
    dev_store = InMemoryDeviceStore()

    class DownStore(InMemoryMemoryStore):
        durable = True

        async def get_summary(self, *a, **k):
            raise MemoryStoreUnavailable("down")

        async def list_approved(self, *a, **k):
            raise MemoryStoreUnavailable("down")

    app.dependency_overrides[devices_api.store_dependency] = lambda: dev_store
    app.dependency_overrides[devices_api.limiter_dependency] = lambda: RateLimiter(1000, 600)
    app.dependency_overrides[memory_api.memory_store_dependency] = lambda: DownStore()
    client = TestClient(app)
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    # Context fails safe with a valid fallback packet (Android continues locally).
    resp = context(client, hdr)
    assert resp.status_code == 200
    assert resp.json()["fallback"] is True
    # Summary GET surfaces a clean 503.
    assert client.get("/v1/memory/sessions/s1/summary", headers=hdr).status_code == 503
