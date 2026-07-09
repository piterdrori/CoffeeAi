"""Stage 5: memory learning and approval workflow tests.

Uses in-memory device + memory stores (no Supabase). Covers consent, extraction, novelty,
status transitions, supersession, consolidation, idempotency, isolation, and context no-writes.
"""
from __future__ import annotations

import uuid

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import devices_api
import hermes
import memory_api
import memory_learning
from devices import InMemoryDeviceStore, RateLimiter
from memory_learning import LEARNING_VERSION, build_memory_proposals, propose_consolidation
from memory_store import InMemoryMemoryStore


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


def candidates(client, hdr, **kw):
    payload = {
        "request_id": kw.pop("request_id", str(uuid.uuid4())),
        "session_id": kw.pop("session_id", "sess-1"),
        "turn_id": kw.pop("turn_id", "turn-1"),
        "language": "en",
        "user_message_summary": kw.pop("user_message_summary", ""),
        "assistant_response_summary": kw.pop("assistant_response_summary", ""),
        "explicit_user_statements": kw.pop("explicit_user_statements", []),
        "consent": kw.pop("consent", {"allow_memory_proposals": True, "allow_sensitive_memory": False}),
    }
    payload.update(kw)
    return client.post("/v1/memory/candidates", json=payload, headers=hdr)


# --- 1 consent disabled ---
def test_consent_disabled_creates_no_proposals():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r = candidates(
        client, hdr,
        explicit_user_statements=[{"text": "I prefer strong coffee", "category_hint": "preference"}],
        consent={"allow_memory_proposals": False, "allow_sensitive_memory": False},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["proposals_created"] == 0
    assert body["consent_blocked"] is True
    assert "consent_disabled" in body.get("reasons", [])
    assert len(store.memories) == 0


# --- 2 sensitive blocked ---
def test_sensitive_memory_blocked_without_consent():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r = candidates(
        client, hdr,
        explicit_user_statements=[{
            "text": "I prefer medical health coffee for my diagnosis",
            "category_hint": "preference",
        }],
        consent={"allow_memory_proposals": True, "allow_sensitive_memory": False},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["proposals_created"] == 0
    assert body["sensitive_skipped"] >= 1


# --- 3 explicit preference ---
def test_explicit_preference_creates_proposed():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r = candidates(
        client, hdr,
        explicit_user_statements=[{"text": "I prefer strong coffee", "category_hint": "preference"}],
    )
    assert r.status_code == 200
    body = r.json()
    assert body["proposals_created"] == 1
    assert body["learning_version"] == LEARNING_VERSION
    mid = body["proposal_ids"][0]
    assert store.memories[mid]["status"] == "proposed"
    assert store.memories[mid]["type"] == "preference"


# --- 4 assistant speculation ignored ---
def test_assistant_speculation_creates_no_memory():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r = candidates(
        client, hdr,
        user_message_summary="What milk should I use?",
        assistant_response_summary="Maybe you prefer oat milk. I think soy might work.",
        explicit_user_statements=[],
    )
    assert r.status_code == 200
    assert r.json()["proposals_created"] == 0
    assert len(store.memories) == 0


# --- 5 temporary request ---
def test_temporary_request_creates_no_memory():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r = candidates(
        client, hdr,
        explicit_user_statements=[{"text": "Remind me later just this once about descaling", "category_hint": "preference"}],
    )
    assert r.status_code == 200
    assert r.json()["proposals_created"] == 0


# --- 6 exact duplicate ---
def test_exact_duplicate_skipped():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r1 = candidates(
        client, hdr, request_id="req-dup-1", turn_id="t1",
        explicit_user_statements=[{"text": "I prefer strong coffee", "category_hint": "preference"}],
    )
    assert r1.json()["proposals_created"] == 1
    # Approve so novelty sees approved
    mid = r1.json()["proposal_ids"][0]
    client.post(f"/v1/memory/proposals/{mid}/approve", headers=hdr)
    r2 = candidates(
        client, hdr, request_id="req-dup-2", turn_id="t2",
        explicit_user_statements=[{"text": "I prefer strong coffee", "category_hint": "preference"}],
    )
    assert r2.json()["proposals_created"] == 0
    assert r2.json()["duplicates_skipped"] >= 1


# --- 7 near-duplicate ---
def test_near_duplicate_skipped():
    _, client, _ = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r1 = candidates(
        client, hdr, request_id="req-near-1", turn_id="t1",
        explicit_user_statements=[{"text": "I prefer strong coffee always", "category_hint": "preference"}],
    )
    mid = r1.json()["proposal_ids"][0]
    client.post(f"/v1/memory/proposals/{mid}/approve", headers=hdr)
    r2 = candidates(
        client, hdr, request_id="req-near-2", turn_id="t2",
        explicit_user_statements=[{"text": "I prefer strong coffee always", "category_hint": "preference"}],
    )
    # exact same after normalize — counted as duplicate; also test slight variant
    r3 = candidates(
        client, hdr, request_id="req-near-3", turn_id="t3",
        explicit_user_statements=[{"text": "I  prefer   strong coffee always", "category_hint": "preference"}],
    )
    assert r3.json()["proposals_created"] == 0
    assert r3.json()["duplicates_skipped"] >= 1


# --- 8 changed preference → proposed replacement ---
def test_changed_preference_becomes_proposed_replacement():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r1 = candidates(
        client, hdr, request_id="req-milk-1", turn_id="t1",
        explicit_user_statements=[{"text": "I prefer soy milk", "category_hint": "preference"}],
    )
    old_id = r1.json()["proposal_ids"][0]
    client.post(f"/v1/memory/proposals/{old_id}/approve", headers=hdr)
    r2 = candidates(
        client, hdr, request_id="req-milk-2", turn_id="t2",
        explicit_user_statements=[{"text": "I prefer oat milk", "category_hint": "preference"}],
    )
    assert r2.json()["proposals_created"] == 1
    new_id = r2.json()["proposal_ids"][0]
    meta = store.memories[new_id]["metadata"]
    assert meta.get("supersedes_memory_id") == old_id
    assert store.memories[new_id]["status"] == "proposed"


# --- 9 contradiction marked ---
def test_contradiction_marked():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r1 = candidates(
        client, hdr, request_id="req-conflict-1", turn_id="t1",
        explicit_user_statements=[{"text": "I prefer soy milk", "category_hint": "preference"}],
    )
    assert r1.status_code == 200, r1.text
    old_id = r1.json()["proposal_ids"][0]
    client.post(f"/v1/memory/proposals/{old_id}/approve", headers=hdr)
    r2 = candidates(
        client, hdr, request_id="req-conflict-2", turn_id="t2",
        explicit_user_statements=[{"text": "I prefer oat milk", "category_hint": "preference"}],
    )
    new_id = r2.json()["proposal_ids"][0]
    assert store.memories[new_id]["metadata"].get("conflict") is True


# --- 10 never auto-approved ---
def test_proposal_never_auto_approved():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    for i in range(3):
        candidates(
            client, hdr, request_id=f"req-auto-{i}", turn_id=f"t{i}",
            explicit_user_statements=[{"text": f"I prefer roast level {i}", "category_hint": "preference"}],
        )
    assert all(m["status"] == "proposed" for m in store.memories.values())


# --- 11 proposed → approved ---
def test_valid_proposed_to_approved():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r = candidates(
        client, hdr,
        explicit_user_statements=[{"text": "I prefer strong coffee", "category_hint": "preference"}],
    )
    mid = r.json()["proposal_ids"][0]
    ar = client.post(f"/v1/memory/proposals/{mid}/approve", headers=hdr)
    assert ar.status_code == 200
    assert ar.json()["status"] == "approved"
    assert store.memories[mid]["status"] == "approved"
    assert store.memories[mid]["reviewed_by"] == "device_user"


# --- 12 proposed → rejected ---
def test_valid_proposed_to_rejected():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r = candidates(
        client, hdr,
        explicit_user_statements=[{"text": "I prefer strong coffee", "category_hint": "preference"}],
    )
    mid = r.json()["proposal_ids"][0]
    rr = client.post(
        f"/v1/memory/proposals/{mid}/reject",
        json={"rejection_reason": "not accurate"},
        headers=hdr,
    )
    assert rr.status_code == 200
    assert rr.json()["status"] == "rejected"
    assert any(a["action"] == "memory_reject" for a in store.audit)


# --- 13 rejected → approved blocked ---
def test_invalid_rejected_to_approved_blocked():
    _, client, _ = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r = candidates(
        client, hdr,
        explicit_user_statements=[{"text": "I prefer strong coffee", "category_hint": "preference"}],
    )
    mid = r.json()["proposal_ids"][0]
    client.post(f"/v1/memory/proposals/{mid}/reject", headers=hdr)
    ar = client.post(f"/v1/memory/proposals/{mid}/approve", headers=hdr)
    assert ar.status_code == 409
    assert ar.json()["detail"] == "invalid_status_transition"
    pr = client.patch(f"/v1/memory/items/{mid}", json={"status": "approved"}, headers=hdr)
    assert pr.status_code == 409


# --- 14 deleted → approved blocked ---
def test_invalid_deleted_to_approved_blocked():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r = candidates(
        client, hdr,
        explicit_user_statements=[{"text": "I prefer strong coffee", "category_hint": "preference"}],
    )
    mid = r.json()["proposal_ids"][0]
    client.delete(f"/v1/memory/items/{mid}", headers=hdr)
    # soft-deleted: get returns None; patch should 404
    pr = client.patch(f"/v1/memory/items/{mid}", json={"status": "approved"}, headers=hdr)
    assert pr.status_code == 404
    assert store.memories[mid]["status"] == "deleted"


# --- 15 supersession ---
def test_approved_replacement_supersedes_old():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r1 = candidates(
        client, hdr, request_id="req-super-1", turn_id="t1",
        explicit_user_statements=[{"text": "I prefer soy milk", "category_hint": "preference"}],
    )
    assert r1.status_code == 200, r1.text
    old_id = r1.json()["proposal_ids"][0]
    client.post(f"/v1/memory/proposals/{old_id}/approve", headers=hdr)
    r2 = candidates(
        client, hdr, request_id="req-super-2", turn_id="t2",
        explicit_user_statements=[{"text": "I prefer oat milk", "category_hint": "preference"}],
    )
    new_id = r2.json()["proposal_ids"][0]
    ar = client.post(f"/v1/memory/proposals/{new_id}/approve", headers=hdr)
    assert ar.status_code == 200
    assert store.memories[new_id]["status"] == "approved"
    assert store.memories[old_id]["status"] == "superseded"


# --- 16 superseded excluded from context ---
def test_superseded_excluded_from_context():
    _, client, _ = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r1 = candidates(
        client, hdr, request_id="req-excl-1", turn_id="t1",
        explicit_user_statements=[{"text": "I prefer soy milk", "category_hint": "preference"}],
    )
    assert r1.status_code == 200, r1.text
    old_id = r1.json()["proposal_ids"][0]
    client.post(f"/v1/memory/proposals/{old_id}/approve", headers=hdr)
    r2 = candidates(
        client, hdr, request_id="req-excl-2", turn_id="t2",
        explicit_user_statements=[{"text": "I prefer oat milk", "category_hint": "preference"}],
    )
    new_id = r2.json()["proposal_ids"][0]
    client.post(f"/v1/memory/proposals/{new_id}/approve", headers=hdr)
    ctx = client.post(
        "/v1/memory/context",
        json={"session_id": "s1", "query": "what milk do I prefer", "language": "en", "context_token_budget": 800},
        headers=hdr,
    ).json()
    contents = [m["content"] for m in ctx.get("user_profile", []) + ctx.get("relevant_memories", [])]
    assert any("oat milk" in c for c in contents)
    assert not any("soy milk" in c for c in contents)


# --- 17 approval audit ---
def test_approval_audit_created():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r = candidates(
        client, hdr,
        explicit_user_statements=[{"text": "I prefer strong coffee", "category_hint": "preference"}],
    )
    mid = r.json()["proposal_ids"][0]
    client.post(f"/v1/memory/proposals/{mid}/approve", headers=hdr)
    assert any(a["action"] == "memory_approve" and a.get("memory_id") == mid for a in store.audit)


# --- 18 rejection audit ---
def test_rejection_audit_created():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r = candidates(
        client, hdr,
        explicit_user_statements=[{"text": "I prefer strong coffee", "category_hint": "preference"}],
    )
    mid = r.json()["proposal_ids"][0]
    client.post(f"/v1/memory/proposals/{mid}/reject", json={"rejection_reason": "nope"}, headers=hdr)
    assert any(a["action"] == "memory_reject" for a in store.audit)


# --- 19 correction audit links old/new ---
def test_correction_audit_links_old_new():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r1 = candidates(
        client, hdr, request_id="req-corr-1", turn_id="t1",
        explicit_user_statements=[{"text": "I prefer soy milk", "category_hint": "preference"}],
    )
    old_id = r1.json()["proposal_ids"][0]
    client.post(f"/v1/memory/proposals/{old_id}/approve", headers=hdr)
    r2 = candidates(
        client, hdr, request_id="req-corr-2", turn_id="t2",
        explicit_user_statements=[{"text": "I prefer oat milk", "category_hint": "preference"}],
    )
    new_id = r2.json()["proposal_ids"][0]
    client.post(f"/v1/memory/proposals/{new_id}/approve", headers=hdr)
    approve_audits = [a for a in store.audit if a["action"] == "memory_approve" and a.get("memory_id") == new_id]
    assert approve_audits
    assert approve_audits[-1]["details"].get("supersedes_memory_id") == old_id
    assert approve_audits[-1]["details"].get("correction") is True


# --- 20 idempotent replay ---
def test_idempotent_replay_no_duplicate():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    rid, tid = "req-idem-1", "turn-idem-1"
    r1 = candidates(
        client, hdr, request_id=rid, turn_id=tid,
        explicit_user_statements=[{"text": "I prefer strong coffee", "category_hint": "preference"}],
    )
    assert r1.json()["proposals_created"] == 1
    ids1 = r1.json()["proposal_ids"]
    r2 = candidates(
        client, hdr, request_id=rid, turn_id=tid,
        explicit_user_statements=[{"text": "I prefer strong coffee", "category_hint": "preference"}],
    )
    assert r2.json().get("idempotent_replay") is True
    assert r2.json()["proposals_created"] == 0
    assert r2.json()["proposal_ids"] == ids1
    assert len([m for m in store.memories.values() if (m.get("metadata") or {}).get("request_id") == rid]) == 1


# --- 21 cross-device blocked ---
def test_cross_device_proposal_access_blocked():
    _, client, _ = build()
    _, a = register(client, "install-aaaaaaaaaaaaaaaa")
    _, b = register(client, "install-bbbbbbbbbbbbbbbb")
    r = candidates(
        client, a,
        explicit_user_statements=[{"text": "I prefer strong coffee", "category_hint": "preference"}],
    )
    mid = r.json()["proposal_ids"][0]
    assert client.get(f"/v1/memory/proposals/{mid}", headers=b).status_code == 404
    assert client.post(f"/v1/memory/proposals/{mid}/approve", headers=b).status_code == 404


# --- 22 raw transcript not required ---
def test_raw_transcript_not_required():
    _, client, _ = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r = candidates(
        client, hdr,
        explicit_user_statements=[{"text": "My machine model is X200", "category_hint": "profile"}],
    )
    assert r.status_code == 200
    assert r.json()["proposals_created"] == 1


# --- 23 raw audio rejected ---
def test_raw_audio_rejected():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r = candidates(
        client, hdr,
        explicit_user_statements=[{"text": "I prefer strong coffee", "category_hint": "preference"}],
        raw_audio="base64blob",
    )
    assert r.status_code == 200
    assert r.json()["proposals_created"] == 0
    assert "raw_audio_rejected" in r.json().get("reasons", [])
    assert len(store.memories) == 0


# --- 24 secret/token rejected ---
def test_secret_token_like_content_rejected():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    r = candidates(
        client, hdr,
        explicit_user_statements=[{
            "text": "I prefer password: hunter2 for my account",
            "category_hint": "preference",
        }],
    )
    assert r.status_code == 200
    assert r.json()["proposals_created"] == 0
    assert r.json()["sensitive_skipped"] >= 1
    assert len(store.memories) == 0


# --- 25 consolidation proposed semantic ---
def test_consolidation_produces_proposed_semantic():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    # Seed three approved episodic with same subject via direct create + approve
    for i, day in enumerate(("Monday", "Wednesday", "Friday")):
        cr = client.post("/v1/memory/items", json={
            "type": "episodic",
            "content": f"The milk tube clogged on {day}",
            "metadata": {"normalized_subject": "milk tube clogged"},
        }, headers=hdr)
        mid = cr.json()["id"]
        # Force approved via store (items patch approve works for proposed)
        client.post(f"/v1/memory/proposals/{mid}/approve", headers=hdr)
    r = client.post("/v1/memory/consolidate", headers=hdr)
    assert r.status_code == 200
    body = r.json()
    assert body["proposals_created"] == 1
    assert body["status"] == "proposed"
    sid = body["proposal_ids"][0]
    assert store.memories[sid]["type"] == "semantic"
    assert store.memories[sid]["status"] == "proposed"


# --- 26 consolidation does not delete episodes ---
def test_consolidation_does_not_delete_episodes():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    ep_ids = []
    for day in ("Monday", "Wednesday", "Friday"):
        cr = client.post("/v1/memory/items", json={
            "type": "episodic",
            "content": f"The milk tube clogged on {day}",
            "metadata": {"normalized_subject": "milk tube clogged"},
        }, headers=hdr)
        mid = cr.json()["id"]
        client.post(f"/v1/memory/proposals/{mid}/approve", headers=hdr)
        ep_ids.append(mid)
    r = client.post("/v1/memory/consolidate", headers=hdr)
    assert r.json()["episodes_preserved"] == 3
    for eid in ep_ids:
        assert store.memories[eid]["status"] == "approved"
        assert store.memories[eid].get("deleted_at") is None


# --- 27 context creates zero memory writes ---
def test_context_retrieval_creates_zero_memory_writes():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    before = len(store.memories)
    client.post(
        "/v1/memory/context",
        json={"session_id": "s1", "query": "I prefer oat milk", "language": "en", "context_token_budget": 800},
        headers=hdr,
    )
    assert len(store.memories) == before


# --- 28 backend LLM not invoked ---
def test_backend_llm_not_invoked():
    # Pure functions only — hermes learning re-exports have no LLM hooks
    assert hasattr(hermes, "identify_candidate_facts")
    assert hasattr(hermes, "build_memory_proposals")
    assert hasattr(hermes, "propose_consolidation")
    assert hermes.HERMES_LEARNING_VERSION == LEARNING_VERSION
    result = build_memory_proposals(
        payload={
            "request_id": "x",
            "session_id": "s",
            "turn_id": "t",
            "language": "en",
            "user_message_summary": "",
            "explicit_user_statements": [{"text": "I prefer strong coffee", "category_hint": "preference"}],
            "consent": {"allow_memory_proposals": True, "allow_sensitive_memory": False},
        },
        existing_memories=[],
    )
    assert all(p["status"] == "proposed" for p in result["proposals"])
    assert result["learning_version"] == LEARNING_VERSION


# --- 29 store failure leaves no active memory ---
def test_store_failure_leaves_no_active_memory():
    _, client, store = build()
    _, hdr = register(client, "install-aaaaaaaaaaaaaaaa")
    store.fail_writes = True
    r = candidates(
        client, hdr,
        explicit_user_statements=[{"text": "I prefer strong coffee", "category_hint": "preference"}],
    )
    assert r.status_code == 200
    assert r.json().get("store_unavailable") is True
    assert r.json()["proposals_created"] == 0
    assert not any(m.get("status") == "approved" for m in store.memories.values())


# --- 30 unit: transitions + hermes helpers ---
def test_status_transition_matrix():
    assert memory_learning.validate_status_transition("proposed", "approved")
    assert memory_learning.validate_status_transition("proposed", "rejected")
    assert memory_learning.validate_status_transition("approved", "superseded")
    assert not memory_learning.validate_status_transition("rejected", "approved")
    assert not memory_learning.validate_status_transition("deleted", "approved")
    assert not memory_learning.validate_status_transition("superseded", "approved")
    assert not memory_learning.validate_status_transition("approved", "proposed")


def test_propose_consolidation_unit():
    episodes = [
        {"id": "1", "type": "episodic", "status": "approved", "content": "milk tube blocked Monday",
         "metadata": {"normalized_subject": "milk tube blocked"}, "updated_at": "2026-01-01"},
        {"id": "2", "type": "episodic", "status": "approved", "content": "milk tube blocked Wednesday",
         "metadata": {"normalized_subject": "milk tube blocked"}, "updated_at": "2026-01-02"},
        {"id": "3", "type": "episodic", "status": "approved", "content": "milk tube blocked Friday",
         "metadata": {"normalized_subject": "milk tube blocked"}, "updated_at": "2026-01-03"},
    ]
    prop = propose_consolidation(episodes)
    assert prop is not None
    assert prop["status"] == "proposed"
    assert prop["type"] == "semantic"
    assert len(prop["metadata"]["consolidation_of"]) == 3
