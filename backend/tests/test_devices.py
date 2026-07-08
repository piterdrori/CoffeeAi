"""Stage 1 device-identity tests. No production credentials / Supabase required — an in-memory
store is injected via FastAPI dependency overrides."""
from __future__ import annotations

import logging

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import devices_api
from devices import (
    DeviceStoreUnavailable,
    InMemoryDeviceStore,
    RateLimiter,
    hash_token,
    validate_install_id,
)

INSTALL_ID = "install-abcdef0123456789"  # valid: 16-128 chars, [A-Za-z0-9_-]


def build_client(store, limiter=None):
    app = FastAPI()
    app.include_router(devices_api.router)
    app.dependency_overrides[devices_api.store_dependency] = lambda: store
    app.dependency_overrides[devices_api.limiter_dependency] = lambda: (
        limiter or RateLimiter(100, 600)
    )
    return TestClient(app)


def register(client, install_id=INSTALL_ID, app_version="1.4.12"):
    return client.post(
        "/v1/devices/register",
        json={"install_id": install_id, "platform": "android", "app_version": app_version},
    )


# 1 — first device registration
def test_first_registration_returns_device_id_and_token():
    store = InMemoryDeviceStore()
    client = build_client(store)
    resp = register(client)
    assert resp.status_code == 200
    body = resp.json()
    assert body["device_id"]
    assert body["device_token"]
    assert body["token_type"] == "Bearer"


# 2 — duplicate registration is idempotent (same device, no duplicate row)
def test_duplicate_registration_is_idempotent():
    store = InMemoryDeviceStore()
    client = build_client(store)
    first = register(client).json()
    second = register(client).json()
    assert first["device_id"] == second["device_id"]
    assert len(store._debug_rows()) == 1
    # Rotation: a fresh token is issued on repeat registration.
    assert first["device_token"] != second["device_token"]


# 3 — only the salted hash is stored, never the raw token
def test_token_is_stored_hashed():
    store = InMemoryDeviceStore()
    client = build_client(store)
    token = register(client).json()["device_token"]
    rows = store._debug_rows()
    assert len(rows) == 1
    stored = rows[0]["token_hash"]
    assert stored != token
    assert stored == hash_token(token)


# 4 — valid bearer token authenticates
def test_valid_bearer_authenticates():
    store = InMemoryDeviceStore()
    client = build_client(store)
    reg = register(client).json()
    resp = client.get("/v1/devices/me", headers={"Authorization": f"Bearer {reg['device_token']}"})
    assert resp.status_code == 200
    assert resp.json()["device_id"] == reg["device_id"]


# 5 — invalid token is rejected
def test_invalid_token_rejected():
    store = InMemoryDeviceStore()
    client = build_client(store)
    register(client)
    resp = client.get("/v1/devices/me", headers={"Authorization": "Bearer not-a-real-token"})
    assert resp.status_code == 401


# 6 — revoked token is rejected
def test_revoked_token_rejected():
    store = InMemoryDeviceStore()
    client = build_client(store)
    reg = register(client).json()
    # Revoke directly in the store.
    store._by_id[reg["device_id"]]["revoked_at"] = "2026-01-01T00:00:00+00:00"
    resp = client.get("/v1/devices/me", headers={"Authorization": f"Bearer {reg['device_token']}"})
    assert resp.status_code == 401


# 7 — missing token is rejected
def test_missing_token_rejected():
    store = InMemoryDeviceStore()
    client = build_client(store)
    assert client.get("/v1/devices/me").status_code == 401


# 8 — install_id validation
def test_install_id_validation():
    assert validate_install_id("install-abcdef0123456789")
    assert not validate_install_id("short")
    assert not validate_install_id("has spaces and !!!")
    assert not validate_install_id(None)

    store = InMemoryDeviceStore()
    client = build_client(store)
    # Bad charset but valid length -> route-level 400.
    bad = client.post("/v1/devices/register", json={"install_id": "bad id with spaces!!"})
    assert bad.status_code == 400
    # Too short -> pydantic 422.
    short = client.post("/v1/devices/register", json={"install_id": "tooshort"})
    assert short.status_code == 422


# 9 — database unavailable response
def test_database_unavailable_health_and_register():
    class UnavailableStore(InMemoryDeviceStore):
        durable = True

        async def health(self) -> dict[str, bool | str]:
            return {"readable": False, "writable": False}

        async def get_by_install_id(self, install_id):
            raise DeviceStoreUnavailable("down")

    store = UnavailableStore()
    client = build_client(store)
    health = client.get("/v1/health/database").json()
    assert health["status"] == "degraded"
    assert health["database"] == "unavailable"
    assert health["readable"] is False
    # Register should fail safe with 503, not 500.
    assert register(client).status_code == 503


# 10 — registration rate-limit behavior
def test_registration_rate_limited():
    store = InMemoryDeviceStore()
    limiter = RateLimiter(max_per_window=2, window_seconds=600)
    client = build_client(store, limiter=limiter)
    assert register(client, install_id="install-aaaaaaaaaaaaaaaa").status_code == 200
    assert register(client, install_id="install-bbbbbbbbbbbbbbbb").status_code == 200
    assert register(client, install_id="install-cccccccccccccccc").status_code == 429


# 11 — no raw token appears in logs
def test_no_raw_token_in_logs(caplog):
    store = InMemoryDeviceStore()
    client = build_client(store)
    with caplog.at_level(logging.DEBUG):
        token = register(client).json()["device_token"]
        client.get("/v1/devices/me", headers={"Authorization": f"Bearer {token}"})
    assert token not in caplog.text


# 15 (backend portion) — the shared API key cannot authorize device-scoped routes
def test_shared_key_cannot_access_device_routes():
    store = InMemoryDeviceStore()
    client = build_client(store)
    register(client)
    # Presenting only the legacy shared key (no bearer) must be rejected by require_device.
    resp = client.get("/v1/devices/me", headers={"X-API-Key": "dev-api-key-change-me"})
    assert resp.status_code == 401


def test_health_reports_durability_flag():
    store = InMemoryDeviceStore()
    client = build_client(store)
    body = client.get("/v1/health/database").json()
    assert body["status"] == "ok"
    assert body["durable"] is False
    assert body["readable"] is True
    assert body["writable"] is True
