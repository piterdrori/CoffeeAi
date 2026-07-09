"""Tests for knowledge chunk_index normalization and parent-document rollback."""
from __future__ import annotations

from unittest.mock import AsyncMock, patch

import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import devices_api
import memory_api
from config import settings
from devices import InMemoryDeviceStore, RateLimiter
from memory_store import (
    InMemoryMemoryStore,
    MemoryStoreUnavailable,
    resolve_chunk_index,
)


def test_resolve_chunk_index_omitted_and_null():
    assert resolve_chunk_index({}, 0) == 0
    assert resolve_chunk_index({"chunk_index": None}, 2) == 2
    assert resolve_chunk_index({"chunk_index": 7}, 0) == 7


def test_inmemory_omitted_indexes_are_positional():
    store = InMemoryMemoryStore()

    async def run():
        doc = await store.create_document({
            "product": "p", "title": "t", "version": "1", "locale": "en",
            "trust_level": "qa", "source": "test",
        })
        rows = await store.add_chunks(doc["id"], [
            {"content": "a"},
            {"content": "b"},
            {"content": "c"},
        ])
        assert [r["chunk_index"] for r in rows] == [0, 1, 2]

    import asyncio
    asyncio.get_event_loop().run_until_complete(run()) if False else None
    import anyio
    anyio.run(run)


def test_inmemory_null_chunk_index_replaced():
    store = InMemoryMemoryStore()

    async def run():
        doc = await store.create_document({
            "product": "p", "title": "t", "version": "1", "locale": "en",
            "trust_level": "qa", "source": "test",
        })
        rows = await store.add_chunks(doc["id"], [
            {"content": "a", "chunk_index": None},
            {"content": "b", "chunk_index": None},
        ])
        assert [r["chunk_index"] for r in rows] == [0, 1]
        assert all(isinstance(r["chunk_index"], int) for r in rows)

    import anyio
    anyio.run(run)


def test_inmemory_explicit_chunk_index_preserved():
    store = InMemoryMemoryStore()

    async def run():
        doc = await store.create_document({
            "product": "p", "title": "t", "version": "1", "locale": "en",
            "trust_level": "qa", "source": "test",
        })
        rows = await store.add_chunks(doc["id"], [
            {"content": "a", "chunk_index": 5},
            {"content": "b", "chunk_index": 9},
        ])
        assert [r["chunk_index"] for r in rows] == [5, 9]

    import anyio
    anyio.run(run)


def test_supabase_payload_never_contains_null_chunk_index():
    """Build the same payload shape SupabaseMemoryStore.add_chunks would send."""
    chunks = [
        {"content": "a", "chunk_index": None},
        {"content": "b"},
        {"content": "c", "chunk_index": 3},
    ]
    payload = [{
        "document_id": "doc-1",
        "chunk_index": resolve_chunk_index(c, i),
        "title": c.get("title"),
        "content": c["content"],
        "metadata": c.get("metadata", {}),
    } for i, c in enumerate(chunks)]
    assert [p["chunk_index"] for p in payload] == [0, 1, 3]
    assert all(p["chunk_index"] is not None for p in payload)
    assert all(isinstance(p["chunk_index"], int) for p in payload)


def build_client(mem_store=None):
    app = FastAPI()
    app.include_router(devices_api.router)
    app.include_router(memory_api.router)
    dev_store = InMemoryDeviceStore()
    mem_store = mem_store or InMemoryMemoryStore()
    app.dependency_overrides[devices_api.store_dependency] = lambda: dev_store
    app.dependency_overrides[devices_api.limiter_dependency] = lambda: RateLimiter(1000, 600)
    app.dependency_overrides[memory_api.memory_store_dependency] = lambda: mem_store
    return TestClient(app), mem_store


def register(client, install_id="install-aaaaaaaaaaaaaaaa"):
    r = client.post("/v1/devices/register", json={"install_id": install_id})
    assert r.status_code == 200
    body = r.json()
    return body["device_id"], {"Authorization": f"Bearer {body['device_token']}"}


def test_admin_create_assigns_positional_indexes(monkeypatch):
    monkeypatch.setattr(settings, "KNOWLEDGE_ADMIN_KEY", "admin-secret")
    client, mem = build_client()
    _, hdr = register(client)
    resp = client.post(
        "/v1/memory/knowledge/documents",
        headers={"X-Admin-Key": "admin-secret"},
        json={
            "product": "STAGE3_ADMIN_SMOKE", "title": "t", "version": "test-v1", "locale": "en",
            "trust_level": "qa", "source": "smoke-test",
            "chunks": [{"content": "one"}, {"content": "two"}, {"content": "three"}],
        },
    )
    assert resp.status_code == 200
    doc_id = resp.json()["document_id"]
    chunks = [c for c in mem.chunks.values() if c["document_id"] == doc_id]
    chunks.sort(key=lambda c: c["chunk_index"])
    assert [c["chunk_index"] for c in chunks] == [0, 1, 2]


def test_admin_create_preserves_explicit_indexes(monkeypatch):
    monkeypatch.setattr(settings, "KNOWLEDGE_ADMIN_KEY", "admin-secret")
    client, mem = build_client()
    register(client)
    resp = client.post(
        "/v1/memory/knowledge/documents",
        headers={"X-Admin-Key": "admin-secret"},
        json={
            "product": "STAGE3_ADMIN_SMOKE", "title": "t", "version": "test-v1", "locale": "en",
            "trust_level": "qa", "source": "smoke-test",
            "chunks": [
                {"content": "a", "chunk_index": 10},
                {"content": "b", "chunk_index": 20},
            ],
        },
    )
    assert resp.status_code == 200
    doc_id = resp.json()["document_id"]
    indexes = sorted(c["chunk_index"] for c in mem.chunks.values() if c["document_id"] == doc_id)
    assert indexes == [10, 20]


def test_parent_rolled_back_when_chunk_insert_fails(monkeypatch):
    monkeypatch.setattr(settings, "KNOWLEDGE_ADMIN_KEY", "admin-secret")
    store = InMemoryMemoryStore()
    preexisting = None

    async def seed():
        nonlocal preexisting
        preexisting = await store.create_document({
            "product": "keep-me", "title": "pre", "version": "1", "locale": "en",
            "trust_level": "qa", "source": "seed",
        })

    import anyio
    anyio.run(seed)

    original_add = store.add_chunks

    async def boom(document_id, chunks):
        raise MemoryStoreUnavailable("chunk_insert_failed")

    store.add_chunks = boom  # type: ignore[method-assign]
    client, _ = build_client(store)
    register(client)
    resp = client.post(
        "/v1/memory/knowledge/documents",
        headers={"X-Admin-Key": "admin-secret"},
        json={
            "product": "STAGE3_ADMIN_SMOKE", "title": "orphan-candidate", "version": "test-v1",
            "locale": "en", "trust_level": "qa", "source": "smoke-test",
            "chunks": [{"content": "will fail"}],
        },
    )
    assert resp.status_code == 503
    assert resp.json()["detail"] == "memory_store_unavailable"
    # Failed create must not leave a STAGE3 document; preexisting must remain.
    assert preexisting["id"] in store.documents
    assert all(d.get("product") != "STAGE3_ADMIN_SMOKE" for d in store.documents.values())


def test_rollback_does_not_delete_preexisting(monkeypatch):
    monkeypatch.setattr(settings, "KNOWLEDGE_ADMIN_KEY", "admin-secret")
    store = InMemoryMemoryStore()

    async def seed():
        return await store.create_document({
            "product": "other", "title": "keep", "version": "1", "locale": "en",
            "trust_level": "qa", "source": "seed",
        })

    import anyio
    keep = anyio.run(seed)

    async def boom(document_id, chunks):
        raise MemoryStoreUnavailable("fail")

    store.add_chunks = boom  # type: ignore[method-assign]
    client, _ = build_client(store)
    register(client)
    client.post(
        "/v1/memory/knowledge/documents",
        headers={"X-Admin-Key": "admin-secret"},
        json={
            "product": "STAGE3_ADMIN_SMOKE", "title": "t", "version": "test-v1", "locale": "en",
            "trust_level": "qa", "source": "smoke-test",
            "chunks": [{"content": "x"}],
        },
    )
    assert keep["id"] in store.documents
    assert store.documents[keep["id"]]["product"] == "other"


def test_device_bearer_still_forbidden(monkeypatch):
    monkeypatch.setattr(settings, "KNOWLEDGE_ADMIN_KEY", "admin-secret")
    client, _ = build_client()
    _, hdr = register(client)
    resp = client.post(
        "/v1/memory/knowledge/documents",
        headers=hdr,
        json={
            "product": "STAGE3_ADMIN_SMOKE", "title": "t", "version": "test-v1", "locale": "en",
            "trust_level": "qa", "source": "smoke-test", "chunks": [],
        },
    )
    assert resp.status_code == 403


def test_admin_delete_still_works(monkeypatch):
    monkeypatch.setattr(settings, "KNOWLEDGE_ADMIN_KEY", "admin-secret")
    client, mem = build_client()
    register(client)
    created = client.post(
        "/v1/memory/knowledge/documents",
        headers={"X-Admin-Key": "admin-secret"},
        json={
            "product": "STAGE3_ADMIN_SMOKE", "title": "t", "version": "test-v1", "locale": "en",
            "trust_level": "qa", "source": "smoke-test",
            "chunks": [{"content": "harmless"}],
        },
    )
    assert created.status_code == 200
    doc_id = created.json()["document_id"]
    deleted = client.delete(
        f"/v1/memory/knowledge/documents/{doc_id}",
        headers={"X-Admin-Key": "admin-secret"},
    )
    assert deleted.status_code == 200
    assert doc_id not in mem.documents


def test_successful_admin_create_returns_document_and_chunks(monkeypatch):
    monkeypatch.setattr(settings, "KNOWLEDGE_ADMIN_KEY", "admin-secret")
    client, mem = build_client()
    _, hdr = register(client)
    created = client.post(
        "/v1/memory/knowledge/documents",
        headers={"X-Admin-Key": "admin-secret"},
        json={
            "product": "STAGE3_ADMIN_SMOKE", "title": "t", "version": "test-v1", "locale": "en",
            "trust_level": "qa", "source": "smoke-test",
            "chunks": [{"content": "harmless smoke chunk for retrieval"}],
        },
    )
    assert created.status_code == 200
    body = created.json()
    assert body["chunk_count"] == 1
    assert body["document_id"] in mem.documents
    ctx = client.post(
        "/v1/memory/context",
        headers=hdr,
        json={
            "query": "harmless smoke chunk",
            "product": "STAGE3_ADMIN_SMOKE",
            "product_version": "test-v1",
            "language": "en",
        },
    )
    assert ctx.status_code == 200
    snippets = ctx.json().get("knowledge_snippets", [])
    assert any("harmless smoke chunk" in (s.get("content") or "") for s in snippets)
