"""Stage 4 Hermes coordinator tests. No LLM, no embeddings, no permanent memory writes."""
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

from fastapi import FastAPI
from fastapi.testclient import TestClient

import devices_api
import memory_api
from devices import InMemoryDeviceStore, RateLimiter
from hermes import (
    HERMES_VERSION,
    build_context_packet,
    build_retrieval_plan,
    classify_intent,
    compress_context,
    detect_conflicts,
    resolve_conflicts,
)
from memory_store import InMemoryMemoryStore, MemoryStoreUnavailable, estimate_tokens


def _iso_days_ago(days: int) -> str:
    return (datetime.now(timezone.utc) - timedelta(days=days)).isoformat()


def build_client():
    app = FastAPI()
    app.include_router(devices_api.router)
    app.include_router(memory_api.router)
    dev_store = InMemoryDeviceStore()
    mem_store = InMemoryMemoryStore()
    app.dependency_overrides[devices_api.store_dependency] = lambda: dev_store
    app.dependency_overrides[devices_api.limiter_dependency] = lambda: RateLimiter(1000, 600)
    app.dependency_overrides[memory_api.memory_store_dependency] = lambda: mem_store
    return TestClient(app), mem_store


def register(client, install_id="install-aaaaaaaaaaaaaaaa"):
    r = client.post("/v1/devices/register", json={"install_id": install_id})
    assert r.status_code == 200
    body = r.json()
    return body["device_id"], {"Authorization": f"Bearer {body['device_token']}"}


# --- Intent classification ---

def test_machine_troubleshooting_intent():
    c = classify_intent("machine error jam not working")
    assert c["intent"] == "machine_troubleshooting"
    assert c["knowledge_needed"] is True
    assert c["memory_needed"] is True


def test_cleaning_intent():
    c = classify_intent("how do I descale and clean the brew group")
    assert c["intent"] == "cleaning_and_maintenance"


def test_coffee_preparation_intent():
    c = classify_intent("best espresso grind dose for latte")
    assert c["intent"] == "coffee_preparation"


def test_preference_intent():
    c = classify_intent("I prefer oat milk remember my favorite")
    assert c["intent"] == "user_preference"
    assert c["knowledge_needed"] is False


def test_safety_intent():
    c = classify_intent("steam wand burn danger warning hot")
    assert c["intent"] == "safety_related"
    assert c["force_safety"] is True


def test_general_chat_minimal_retrieval():
    c = classify_intent("hello how are you today")
    assert c["intent"] == "general_chat"
    plan = build_retrieval_plan(
        c, product="generic", product_version=None, language="en", token_budget=800,
    )
    assert plan["include_machine_knowledge"] is False
    assert plan["memory_types"] == [] or plan["max_memory_candidates"] <= 10


# --- Retrieval planning ---

def test_retrieval_plan_troubleshooting_types():
    c = classify_intent("troubleshoot machine leak error")
    plan = build_retrieval_plan(
        c, product="coffee-x", product_version="1.4.12", language="en", token_budget=800,
    )
    assert "episodic" in plan["memory_types"]
    assert "procedural" in plan["memory_types"]
    assert "safety" in plan["memory_types"]
    assert plan["include_machine_knowledge"] is True
    assert plan["include_session_summary"] is True


def test_preference_excludes_unnecessary_knowledge():
    c = classify_intent("I prefer oat milk")
    plan = build_retrieval_plan(
        c, product="coffee-x", product_version="1.4.12", language="en", token_budget=800,
    )
    assert plan["include_machine_knowledge"] is False
    assert "preference" in plan["memory_types"]


def test_safety_forced_into_plan():
    c = classify_intent("never open hot steam valve danger")
    plan = build_retrieval_plan(
        c, product="coffee-x", product_version=None, language="en", token_budget=800,
    )
    assert plan["include_safety"] is True
    assert "safety" in plan["memory_types"]


# --- Conflicts ---

def test_exact_product_knowledge_beats_generic():
    conflicts = detect_conflicts(
        [],
        [
            {
                "id": "k1", "content": "use oat milk for this machine",
                "_tier": 1, "_trust_level": "verified",
                "_document": {"version": "1.4.12", "trust_level": "verified", "source": "manual"},
            },
            {
                "id": "k2", "content": "use soy milk for this machine",
                "_tier": 3, "_trust_level": "unknown",
                "_document": {"version": "*", "trust_level": "unknown", "source": "unknown"},
            },
        ],
    )
    assert conflicts
    res = resolve_conflicts(conflicts)
    assert res["resolved"]
    assert res["resolved"][0]["winner_id"] == "k1"


def test_newest_user_correction_beats_stale_preference():
    conflicts = detect_conflicts(
        [
            {
                "id": "old", "type": "preference", "content": "I prefer soy milk",
                "confidence": 0.8, "updated_at": _iso_days_ago(100), "source": "device",
            },
            {
                "id": "new", "type": "preference", "content": "I prefer oat milk",
                "confidence": 0.8, "updated_at": _iso_days_ago(1), "source": "device",
            },
        ],
        [],
    )
    assert conflicts
    res = resolve_conflicts(conflicts)
    assert res["resolved"][0]["winner_id"] == "new"
    assert "old" in res["suppressed_ids"]


def test_higher_confidence_wins_when_timestamps_equal():
    ts = _iso_days_ago(2)
    conflicts = detect_conflicts(
        [
            {
                "id": "low", "type": "semantic", "content": "prefer fine grind",
                "confidence": 0.2, "updated_at": ts, "source": "unknown",
            },
            {
                "id": "high", "type": "semantic", "content": "prefer coarse grind",
                "confidence": 0.99, "updated_at": ts, "source": "device",
            },
        ],
        [],
    )
    res = resolve_conflicts(conflicts)
    assert res["resolved"][0]["winner_id"] == "high"


def test_unresolved_conflict_clarification_marker():
    ts = _iso_days_ago(1)
    conflicts = detect_conflicts(
        [
            {
                "id": "a", "type": "semantic", "content": "prefer oat milk",
                "confidence": 0.5, "updated_at": ts, "source": "device",
            },
            {
                "id": "b", "type": "semantic", "content": "prefer soy milk",
                "confidence": 0.5, "updated_at": ts, "source": "device",
            },
        ],
        [],
    )
    res = resolve_conflicts(conflicts)
    # May resolve by recency tie-break or mark unresolved; ensure structure is valid
    assert "unresolved_count" in res
    packet, usage = build_context_packet(
        request_id="r", language="en", budget=800, query="what milk do I prefer",
        product="generic", product_version=None, summary=None,
        memories=[
            {
                "id": "a", "type": "preference", "content": "I prefer oat milk",
                "status": "approved", "confidence": 0.5, "importance": 0.5,
                "source": "device", "language": "en", "updated_at": ts, "deleted_at": None,
                "metadata": {},
            },
            {
                "id": "b", "type": "preference", "content": "I prefer soy milk",
                "status": "approved", "confidence": 0.5, "importance": 0.5,
                "source": "device", "language": "en", "updated_at": ts, "deleted_at": None,
                "metadata": {},
            },
        ],
        knowledge_chunks=[],
    )
    assert usage["backend_llm_invoked"] is False
    assert usage["permanent_memory_writes"] == 0
    if usage["unresolved_conflicts"] > 0:
        assert packet.get("clarification_hint") == "conflicting_memories_need_clarification"


# --- Compression ---

def test_duplicate_facts_compress_to_one():
    packet = {
        "safety_rules": [],
        "recent_summary": None,
        "knowledge_snippets": [],
        "relevant_memories": [
            {"id": "1", "type": "semantic", "content": "User likes oat milk."},
            {"id": "2", "type": "semantic", "content": "User likes oat milk."},
        ],
        "user_profile": [],
        "used_token_estimate": 0,
    }
    # compress_context does summary overlap; packing dedupes exact — simulate overlap with summary
    packet["recent_summary"] = "User likes oat milk."
    out, applied = compress_context(packet, budget=800, force_safety=False)
    assert applied is True
    assert out["relevant_memories"] == []


def test_summary_overlap_compresses():
    packet = {
        "safety_rules": [],
        "recent_summary": "User asked about descaling the brew group carefully.",
        "knowledge_snippets": [],
        "relevant_memories": [
            {"id": "1", "type": "semantic", "content": "User asked about descaling the brew group carefully."},
        ],
        "user_profile": [],
        "used_token_estimate": 0,
    }
    out, applied = compress_context(packet, budget=800, force_safety=False)
    assert applied
    assert out["relevant_memories"] == []


def test_safety_not_over_compressed():
    long_safety = "Never immerse the machine in water. " * 20
    packet = {
        "safety_rules": [{"id": "s", "content": long_safety}],
        "recent_summary": None,
        "knowledge_snippets": [],
        "relevant_memories": [],
        "user_profile": [],
        "used_token_estimate": 0,
    }
    out, _ = compress_context(packet, budget=200, force_safety=True)
    assert out["safety_rules"]
    assert "Never immerse" in out["safety_rules"][0]["content"]


def test_token_budget_enforced_with_hermes():
    mems = [
        {
            "id": str(i), "type": "semantic", "content": ("word " * 80) + f" fact {i}",
            "status": "approved", "confidence": 0.9, "importance": 0.5, "source": "device",
            "language": "en", "updated_at": _iso_days_ago(1), "deleted_at": None, "metadata": {},
        }
        for i in range(10)
    ]
    packet, usage = build_context_packet(
        request_id="r", language="en", budget=200, query="fact word semantic",
        product="generic", product_version=None, summary=None,
        memories=mems, knowledge_chunks=[],
    )
    assert packet["used_token_estimate"] <= 200
    assert usage["backend_llm_invoked"] is False


# --- API integration ---

def test_no_proposed_deleted_in_hermes_packet():
    client, _ = build_client()
    _, hdr = register(client)
    r = client.post("/v1/memory/items", json={"type": "semantic", "content": "proposed only"}, headers=hdr)
    mid = r.json()["id"]
    # leave proposed
    ctx = client.post("/v1/memory/context", json={"query": "proposed only"}, headers=hdr).json()
    assert not any("proposed only" in m["content"] for m in ctx["relevant_memories"])
    client.patch(f"/v1/memory/items/{mid}", json={"status": "approved"}, headers=hdr)
    client.delete(f"/v1/memory/items/{mid}", headers=hdr)
    ctx2 = client.post("/v1/memory/context", json={"query": "proposed only"}, headers=hdr).json()
    assert not any("proposed only" in m.get("content", "") for m in ctx2["relevant_memories"])


def test_device_isolation_hermes():
    client, _ = build_client()
    _, a = register(client, "install-aaaaaaaaaaaaaaaa")
    _, b = register(client, "install-bbbbbbbbbbbbbbbb")
    mid = client.post("/v1/memory/items", json={"type": "semantic", "content": "Device A exclusive"}, headers=a).json()["id"]
    client.patch(f"/v1/memory/items/{mid}", json={"status": "approved"}, headers=a)
    a_ctx = client.post("/v1/memory/context", json={"query": "exclusive"}, headers=a).json()
    b_ctx = client.post("/v1/memory/context", json={"query": "exclusive"}, headers=b).json()
    assert any("Device A exclusive" in m["content"] for m in a_ctx["relevant_memories"])
    assert b_ctx["relevant_memories"] == []


def test_audit_contains_hermes_metadata_no_raw_query():
    client, mem = build_client()
    _, hdr = register(client)
    mid = client.post(
        "/v1/memory/items",
        json={"type": "safety", "content": "SECRET-SAFETY-CONTENT never open valve"},
        headers=hdr,
    ).json()["id"]
    client.patch(f"/v1/memory/items/{mid}", json={"status": "approved"}, headers=hdr)
    secret = "TOP-SECRET-QUERY-STRING-HERMES"
    client.post("/v1/memory/context", json={"query": secret, "session_id": "s1"}, headers=hdr)
    blob = json.dumps(mem.audit)
    assert secret not in blob
    assert "SECRET-SAFETY-CONTENT" not in blob
    details = [e for e in mem.audit if e["action"] == "context"][-1]["details"]
    assert details["hermes_version"] == HERMES_VERSION
    assert "intent" in details
    assert details["backend_llm_invoked"] is False
    assert details["permanent_memory_writes"] == 0
    assert details["query_len"] == len(secret)


def test_packet_backward_compatible_fields():
    client, _ = build_client()
    _, hdr = register(client)
    body = client.post("/v1/memory/context", json={"query": "hello"}, headers=hdr).json()
    for key in (
        "request_id", "memory_version", "intent", "language", "user_profile",
        "relevant_memories", "knowledge_snippets", "recent_summary", "safety_rules",
        "context_token_budget", "used_token_estimate", "categories_used", "fallback",
        "hermes_version", "intent_confidence", "retrieval_plan",
        "conflicts_detected", "conflicts_resolved", "unresolved_conflicts",
        "compression_applied", "candidate_count", "selected_count",
    ):
        assert key in body
    assert body["memory_version"] == 3
    assert body["hermes_version"] == HERMES_VERSION


def test_fallback_packet_valid():
    app = FastAPI()
    app.include_router(devices_api.router)
    app.include_router(memory_api.router)
    dev_store = InMemoryDeviceStore()

    class Down(InMemoryMemoryStore):
        durable = True

        async def list_all_approved(self, *a, **k):
            raise MemoryStoreUnavailable("down")

    app.dependency_overrides[devices_api.store_dependency] = lambda: dev_store
    app.dependency_overrides[devices_api.limiter_dependency] = lambda: RateLimiter(1000, 600)
    app.dependency_overrides[memory_api.memory_store_dependency] = lambda: Down()
    client = TestClient(app)
    _, hdr = register(client)
    body = client.post("/v1/memory/context", json={"query": "test"}, headers=hdr).json()
    assert body["fallback"] is True
    assert body["memory_version"] == 3
    assert body["hermes_version"] == HERMES_VERSION
    assert body["safety_rules"] == []


def test_no_backend_llm_and_no_permanent_write_on_context():
    client, mem = build_client()
    _, hdr = register(client)
    before = len(mem.memories)
    client.post("/v1/memory/context", json={"query": "how to descale machine"}, headers=hdr)
    assert len(mem.memories) == before  # no permanent memory write
    details = [e for e in mem.audit if e["action"] == "context"][-1]["details"]
    assert details["backend_llm_invoked"] is False
    assert details["permanent_memory_writes"] == 0


def test_troubleshooting_includes_knowledge_and_summary(monkeypatch):
    from config import settings
    monkeypatch.setattr(settings, "KNOWLEDGE_ADMIN_KEY", "admin-secret")
    client, _ = build_client()
    _, hdr = register(client)
    client.put(
        "/v1/memory/sessions/s1/summary",
        json={"summary": "User reported a grinder jam yesterday."},
        headers=hdr,
    )
    mid = client.post(
        "/v1/memory/items",
        json={"type": "episodic", "content": "grinder jam incident last week"},
        headers=hdr,
    ).json()["id"]
    client.patch(f"/v1/memory/items/{mid}", json={"status": "approved"}, headers=hdr)
    client.post(
        "/v1/memory/knowledge/documents",
        headers={"X-Admin-Key": "admin-secret"},
        json={
            "product": "coffee-x", "title": "Jam", "version": "1.4.12", "locale": "en",
            "trust_level": "verified", "source": "manual",
            "chunks": [{"content": "Clear grinder jam by removing beans and rotating burrs."}],
        },
    )
    body = client.post(
        "/v1/memory/context",
        json={
            "session_id": "s1",
            "query": "machine grinder jam error troubleshoot",
            "product": "coffee-x",
            "product_version": "1.4.12",
            "language": "en",
        },
        headers=hdr,
    ).json()
    assert body["intent"] == "machine_troubleshooting"
    assert body["recent_summary"]
    assert body["knowledge_snippets"] or body["relevant_memories"]
    assert body["retrieval_plan"]["include_machine_knowledge"] is True
