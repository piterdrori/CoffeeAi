"""Stage 3 deterministic retrieval and ranking tests."""
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import devices_api
import memory_api
from config import settings
from devices import InMemoryDeviceStore, RateLimiter
from memory_retrieval import (
    RETRIEVAL_STRATEGY_VERSION,
    dedupe_ranked,
    filter_knowledge_candidates,
    filter_memory_candidates,
    normalize_query,
    retrieve_and_pack_context,
    score_knowledge,
    score_memory,
)
from memory_store import InMemoryMemoryStore, MemoryStoreUnavailable, estimate_tokens, utc_now_iso


def _iso_days_ago(days: int) -> str:
    return (datetime.now(timezone.utc) - timedelta(days=days)).isoformat()


def _mem(**kw) -> dict:
    base = {
        "id": kw.pop("id", "m1"),
        "device_id": "dev",
        "type": "semantic",
        "content": "default content",
        "summary": None,
        "confidence": 0.8,
        "importance": 0.5,
        "status": "approved",
        "source": "device",
        "language": "en",
        "product_version": None,
        "sensitivity": "normal",
        "metadata": {},
        "created_at": _iso_days_ago(10),
        "updated_at": _iso_days_ago(1),
        "deleted_at": None,
    }
    base.update(kw)
    return base


def _chunk(**kw) -> dict:
    doc = kw.pop("_document", {
        "product": "coffee-x", "locale": "en", "version": "1.0", "status": "active",
        "trust_level": "verified", "source": "manual",
    })
    base = {"id": "k1", "title": "Guide", "content": "descale machine", "chunk_index": 0, "metadata": {}}
    base.update(kw)
    base["_document"] = doc
    return base


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


def register(client, install_id):
    r = client.post("/v1/devices/register", json={"install_id": install_id})
    assert r.status_code == 200
    body = r.json()
    return body["device_id"], {"Authorization": f"Bearer {body['device_token']}"}


# 1 — exact keyword match ranks above weak match
def test_exact_keyword_ranks_above_weak():
    q = normalize_query("descale procedure")
    strong = _mem(id="a", content="Follow the descale procedure monthly.")
    weak = _mem(id="b", content="User enjoys dark roast coffee.")
    sa = score_memory(strong, q)["score"]
    sb = score_memory(weak, q)["score"]
    assert sa > sb


# 2 — phrase match ranks above token-only match
def test_phrase_match_ranks_above_token_only():
    q = normalize_query("brew group rinse")
    phrase = _mem(id="a", content="Always brew group rinse after each session.")
    tokens = _mem(id="b", content="brew daily rinse water tank")
    assert score_memory(phrase, q)["score"] > score_memory(tokens, q)["score"]


# 3 — safety survives weak relevance
def test_safety_survives_weak_relevance():
    q = normalize_query("weather forecast")
    safety = _mem(id="s", type="safety", content="Never open steam valve while hot.", importance=0.9)
    _, usage = retrieve_and_pack_context(
        request_id="r", language="en", budget=800, query="weather forecast",
        product="coffee-x", product_version=None, summary=None,
        memories=[safety], knowledge_chunks=[],
    )
    assert usage["memory_ids"] == ["s"]


# 4 — proposed/rejected/deleted excluded
def test_hard_filter_excludes_bad_statuses():
    approved = _mem(id="ok", status="approved")
    proposed = _mem(id="p", status="proposed")
    rejected = _mem(id="r", status="rejected")
    deleted = _mem(id="d", status="approved", deleted_at=utc_now_iso())
    out = filter_memory_candidates([approved, proposed, rejected, deleted], language="en", product_version=None)
    assert [m["id"] for m in out] == ["ok"]


# 5 — product mismatch excluded (knowledge)
def test_product_mismatch_excluded():
    chunk = _chunk(_document={"product": "other-machine", "locale": "en", "version": "*", "status": "active", "trust_level": "verified"})
    out = filter_knowledge_candidates([chunk], product="coffee-x", locale="en", product_version="1.0")
    assert out == []


# 6 — product-version exact beats generic
def test_version_exact_beats_generic():
    exact = _chunk(id="e", content="maintenance information for this model", _document={
        "product": "coffee-x", "locale": "en", "version": "1.4.12", "status": "active", "trust_level": "verified",
    })
    generic = _chunk(id="g", content="maintenance information for this model", _document={
        "product": "coffee-x", "locale": "en", "version": "*", "status": "active", "trust_level": "verified",
    })
    filtered = filter_knowledge_candidates(
        [exact, generic], product="coffee-x", locale="en", product_version="1.4.12",
    )
    exact_f = next(c for c in filtered if c["id"] == "e")
    generic_f = next(c for c in filtered if c["id"] == "g")
    assert exact_f["_tier"] < generic_f["_tier"]
    q = normalize_query("descale procedure")
    assert score_knowledge(exact_f, q)["score"] > score_knowledge(generic_f, q)["score"]


# 7 — locale exact beats fallback (tier)
def test_locale_exact_beats_fallback_tier():
    en_us = _chunk(_document={"product": "coffee-x", "locale": "en", "version": "*", "status": "active", "trust_level": "verified"})
    en_gb = _chunk(id="k2", _document={"product": "coffee-x", "locale": "en-gb", "version": "*", "status": "active", "trust_level": "verified"})
    tiers = filter_knowledge_candidates([en_us, en_gb], product="coffee-x", locale="en", product_version=None)
    assert tiers[0]["_tier"] <= tiers[-1]["_tier"]


# 8 — trusted manual beats unknown source
def test_trusted_manual_beats_unknown():
    q = normalize_query("clean machine")
    trusted = _mem(id="t", content="clean machine weekly", source="manual", confidence=1.0)
    unknown = _mem(id="u", content="clean machine weekly", source="unknown", confidence=1.0)
    assert score_memory(trusted, q)["score"] > score_memory(unknown, q)["score"]


# 9 — recent episodic beats stale episodic
def test_recent_episodic_beats_stale():
    q = normalize_query("grinder jam")
    recent = _mem(id="r", type="episodic", content="grinder jam yesterday", updated_at=_iso_days_ago(1))
    stale = _mem(id="s", type="episodic", content="grinder jam old", updated_at=_iso_days_ago(120))
    assert score_memory(recent, q)["score"] > score_memory(stale, q)["score"]


# 10 — stable procedural decays slowly
def test_procedural_decays_slowly():
    q = normalize_query("descale")
    proc_old = _mem(id="p", type="procedural", content="descale steps", updated_at=_iso_days_ago(200))
    epi_old = _mem(id="e", type="episodic", content="descale incident", updated_at=_iso_days_ago(200))
    assert score_memory(proc_old, q)["components"]["recency"] > score_memory(epi_old, q)["components"]["recency"]


# 11 — importance affects ranking
def test_importance_affects_ranking():
    q = normalize_query("milk preference")
    high = _mem(id="h", content="milk preference oat", importance=0.95)
    low = _mem(id="l", content="milk preference soy", importance=0.1)
    assert score_memory(high, q)["score"] > score_memory(low, q)["score"]


# 12 — confidence affects ranking
def test_confidence_affects_ranking():
    q = normalize_query("temperature setting")
    high = _mem(id="h", content="temperature setting 92C", confidence=0.99)
    low = _mem(id="l", content="temperature setting 90C", confidence=0.2)
    assert score_memory(high, q)["score"] > score_memory(low, q)["score"]


# 13 — duplicate memories collapse
def test_duplicate_memories_collapse():
    a = (_mem(id="a", content="same fact here"), {"score": 0.9})
    b = (_mem(id="b", content="same fact here"), {"score": 0.5})
    out = dedupe_ranked([a, b])
    assert len(out) == 1 and out[0][0]["id"] == "a"


# 14 — duplicate knowledge chunks collapse
def test_duplicate_knowledge_collapse():
    c1 = (_chunk(id="1", content="rinse brew group"), {"score": 0.8})
    c2 = (_chunk(id="2", content="rinse brew group"), {"score": 0.6})
    assert len(dedupe_ranked([c1, c2])) == 1


# 15 — summary overlap does not duplicate content
def test_summary_overlap_not_duplicated():
    summary = {"summary": "User asked about descaling the brew group."}
    mem = _mem(id="m", type="semantic", content="User asked about descaling the brew group.")
    packet, _ = retrieve_and_pack_context(
        request_id="r", language="en", budget=2000, query="descale",
        product="generic", product_version=None, summary=summary, memories=[mem], knowledge_chunks=[],
    )
    assert packet["recent_summary"]
    assert packet["relevant_memories"] == []


# 16 — token budget enforced
def test_token_budget_enforced():
    safety = _mem(id="s", type="safety", content="Never immerse the machine in water.")
    big = _mem(id="b", type="semantic", content="word " * 500)
    budget = estimate_tokens(safety["content"]) + 5
    packet, _ = retrieve_and_pack_context(
        request_id="r", language="en", budget=budget, query="test",
        product="generic", product_version=None, summary=None,
        memories=[safety, big], knowledge_chunks=[],
    )
    assert packet["used_token_estimate"] <= budget
    assert packet["safety_rules"]
    if packet["relevant_memories"]:
        assert packet["relevant_memories"][0]["content"].endswith("…")


# 17 — 3–8 highest-value items selected
def test_selected_item_cap():
    mems = [_mem(id=str(i), type="semantic", content=f"fact number {i}") for i in range(20)]
    packet, usage = retrieve_and_pack_context(
        request_id="r", language="en", budget=5000, query="fact",
        product="generic", product_version=None, summary=None, memories=mems, knowledge_chunks=[],
    )
    assert 0 <= usage["selected_count"] <= 8


# 18 — profile does not displace safety/procedure
def test_profile_does_not_displace_safety():
    safety = _mem(id="s", type="safety", content="Do not touch hot surfaces.")
    proc = _mem(id="p", type="procedural", content="How to descale the machine step by step.")
    profiles = [_mem(id=f"pr{i}", type="profile", content=f"My name is profile {i}") for i in range(5)]
    packet, _ = retrieve_and_pack_context(
        request_id="r", language="en", budget=800, query="how to descale",
        product="generic", product_version=None, summary=None,
        memories=[*profiles, proc, safety], knowledge_chunks=[],
    )
    assert packet["safety_rules"]
    assert any("descale" in m["content"].lower() for m in packet["relevant_memories"])


# 19 — audit contains scores/counts but no raw query/content
def test_audit_privacy_fields():
    client, mem_store = build_client()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    mid = client.post("/v1/memory/items", json={"type": "semantic", "content": "SECRET-MEMORY-BODY"}, headers=hdr).json()["id"]
    client.patch(f"/v1/memory/items/{mid}", json={"status": "approved"}, headers=hdr)
    secret = "TOP-SECRET-QUERY-STRING"
    client.post("/v1/memory/context", json={"query": secret, "session_id": "s1"}, headers=hdr)
    blob = json.dumps(mem_store.audit)
    assert secret not in blob
    assert "SECRET-MEMORY-BODY" not in blob
    details = [e for e in mem_store.audit if e["action"] == "context"][-1]["details"]
    assert details["query_len"] == len(secret)
    assert details["retrieval_strategy"] == RETRIEVAL_STRATEGY_VERSION
    assert "candidate_count" in details and "score_range" in details


# 20 — device isolation remains intact
def test_device_isolation_retrieval():
    client, _ = build_client()
    _, a = register(client, "install-aaaaaaaaaaaaaaaa")
    _, b = register(client, "install-bbbbbbbbbbbbbbbb")
    mid = client.post("/v1/memory/items", json={"type": "semantic", "content": "Device A secret"}, headers=a).json()["id"]
    client.patch(f"/v1/memory/items/{mid}", json={"status": "approved"}, headers=a)
    a_ctx = client.post("/v1/memory/context", json={"query": "secret"}, headers=a).json()
    b_ctx = client.post("/v1/memory/context", json={"query": "secret"}, headers=b).json()
    assert any("Device A secret" in m["content"] for m in a_ctx["relevant_memories"])
    assert b_ctx["relevant_memories"] == []


# 21 — device bearer cannot write knowledge
def test_device_cannot_write_knowledge(monkeypatch):
    monkeypatch.setattr(settings, "KNOWLEDGE_ADMIN_KEY", "admin-secret")
    client, _ = build_client()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    resp = client.post(
        "/v1/memory/knowledge/documents",
        headers=hdr,
        json={"product": "coffee-x", "title": "t", "version": "1", "locale": "en",
              "trust_level": "verified", "source": "test", "chunks": []},
    )
    assert resp.status_code == 403


# 22 — admin knowledge create/delete works
def test_admin_knowledge_create_delete(monkeypatch):
    monkeypatch.setattr(settings, "KNOWLEDGE_ADMIN_KEY", "admin-secret")
    client, _ = build_client()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    admin = {"X-Admin-Key": "admin-secret"}
    created = client.post(
        "/v1/memory/knowledge/documents",
        headers=admin,
        json={
            "product": "coffee-x", "title": "Clean", "version": "1.4.12", "locale": "en",
            "trust_level": "verified", "source": "manual",
            "chunks": [{"content": "Rinse brew group weekly."}],
        },
    )
    assert created.status_code == 200
    doc_id = created.json()["document_id"]
    ctx = client.post("/v1/memory/context", json={"query": "rinse brew", "product": "coffee-x"}, headers=hdr).json()
    assert any("Rinse brew group" in c["content"] for c in ctx["knowledge_snippets"])
    deleted = client.delete(f"/v1/memory/knowledge/documents/{doc_id}", headers=admin)
    assert deleted.status_code == 200
    ctx2 = client.post("/v1/memory/context", json={"query": "rinse brew", "product": "coffee-x"}, headers=hdr).json()
    assert ctx2["knowledge_snippets"] == []


# 23 — fallback packet when store unavailable
def test_fallback_when_store_unavailable():
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
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    resp = client.post("/v1/memory/context", json={"query": "test"}, headers=hdr)
    assert resp.status_code == 200
    body = resp.json()
    assert body["fallback"] is True
    assert body["memory_version"] == 3
    assert body["retrieval_strategy"] == RETRIEVAL_STRATEGY_VERSION
    assert body.get("hermes_version") == "hermes-v1"


def test_normalize_query_strips_punctuation_and_stopwords():
    q = normalize_query("  How   do I DESCale?!  ")
    assert "how" not in q["tokens"]
    assert "descale" in q["tokens"]
    assert q["is_how_to"] is True


def test_context_packet_has_stage3_metadata():
    client, _ = build_client()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    resp = client.post("/v1/memory/context", json={"query": "hello"}, headers=hdr)
    body = resp.json()
    assert body["memory_version"] == 3
    assert body["retrieval_strategy"] == RETRIEVAL_STRATEGY_VERSION
    assert "candidate_count" in body and "selected_count" in body
    assert body.get("hermes_version") == "hermes-v1"
