"""Stage 6B-1: Control Center Users read API tests."""
from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.responses import RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.testclient import TestClient

import admin_api
from admin_auth import get_login_limiter
from admin_users import (
    MAX_PAGE_SIZE,
    build_user_row,
    derive_memory_health,
    derive_status,
    display_name_for,
    get_admin_user_detail,
    list_admin_users,
)
from config import settings
from devices import DeviceStoreUnavailable, InMemoryDeviceStore
from memory_store import InMemoryMemoryStore, MemoryStoreUnavailable

WEB_DIR = Path(__file__).resolve().parents[1] / "web"


class FailingAuditMemoryStore(InMemoryMemoryStore):
    async def latest_memory_activity_by_device(self) -> dict[str, str]:
        raise MemoryStoreUnavailable("simulated")

    async def grouped_memory_status_counts(self) -> dict[str, dict[str, int]]:
        raise MemoryStoreUnavailable("simulated")

    async def write_audit(self, entry):
        raise AssertionError("users api must not write audit")

    async def create_memory(self, device_id, data):
        raise AssertionError("users api must not write memory")


class FailingDeviceStore(InMemoryDeviceStore):
    async def list_active_devices(self) -> list[dict[str, Any]]:
        raise DeviceStoreUnavailable("simulated")


@pytest.fixture
def admin_env(monkeypatch):
    monkeypatch.setattr(settings, "CONTROL_CENTER_PASSWORD", "test-admin-password-ok")
    monkeypatch.setattr(settings, "CONTROL_CENTER_USERNAME", "")
    monkeypatch.setattr(settings, "ADMIN_SESSION_SECRET", "test-session-secret-32chars-min!!")
    monkeypatch.setattr(settings, "ADMIN_SESSION_TTL_SECONDS", 8 * 3600)
    monkeypatch.setattr(settings, "ADMIN_LOGIN_MAX_FAILURES", 5)
    monkeypatch.setattr(settings, "ADMIN_LOGIN_WINDOW_SECONDS", 600)
    monkeypatch.setattr(settings, "ADMIN_COOKIE_SECURE", False)
    monkeypatch.setattr(settings, "ADMIN_COOKIE_SAMESITE", "lax")
    get_login_limiter().reset()
    yield
    get_login_limiter().reset()


@pytest.fixture
def stores():
    return InMemoryDeviceStore(), InMemoryMemoryStore()


@pytest.fixture
def client(admin_env, stores, monkeypatch):
    devices, memory = stores
    monkeypatch.setattr(admin_api, "get_device_store", lambda: devices)
    monkeypatch.setattr(admin_api, "get_memory_store", lambda: memory)
    app = FastAPI()
    app.include_router(admin_api.router)

    @app.get("/")
    async def home():
        return RedirectResponse(url="/admin/login", status_code=303)

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    if WEB_DIR.exists():
        app.mount("/static", StaticFiles(directory=str(WEB_DIR)), name="static")
    return TestClient(app, follow_redirects=False)


def _login(client: TestClient):
    assert client.post("/admin/api/login", json={"password": "test-admin-password-ok"}).status_code == 200


async def _create_device(
    store: InMemoryDeviceStore,
    install: str,
    *,
    app_version: str | None = "1.4.12",
    metadata: dict | None = None,
    last_seen: str | None = None,
    revoked: bool = False,
):
    row = await store.create_device(install, "c" * 64, "android", app_version)
    if metadata is not None:
        store._by_id[row["id"]]["metadata"] = dict(metadata)
    if last_seen is not None:
        store._by_id[row["id"]]["last_seen_at"] = last_seen
    if revoked:
        store._by_id[row["id"]]["revoked_at"] = datetime.now(timezone.utc).isoformat()
    return store._by_id[row["id"]]


# --- pure helpers -----------------------------------------------------------------


def test_display_name_fallback():
    assert display_name_for("aaaaaaaa-bbbb-cccc-dddd-eeeeeeee1048") == "Device 1048"
    assert display_name_for("x", {"display_name": "Cafe One"}) == "Cafe One"


def test_memory_health_and_status_logic():
    now = datetime.now(timezone.utc)
    assert derive_memory_health(last_memory_at=None, now=now) == "never_connected"
    assert derive_memory_health(last_memory_at=now.isoformat(), now=now) == "healthy"
    old = (now - timedelta(days=8)).isoformat()
    assert derive_memory_health(last_memory_at=old, now=now) == "offline"
    # slow is never fabricated without latency
    assert derive_status(last_active=now.isoformat(), memory_health="healthy", now=now) == "active"
    assert derive_status(last_active=old, memory_health="healthy", now=now) == "offline"
    assert derive_status(last_active=now.isoformat(), memory_health="slow", now=now) == "needs_attention"


def test_build_user_row_excludes_secrets():
    device = {
        "id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeee1048",
        "token_hash": "secret-hash",
        "install_id": "install-aaaaaaaaaaaa",
        "app_version": None,
        "created_at": "2026-01-01T00:00:00+00:00",
        "last_seen_at": "2026-01-02T00:00:00+00:00",
        "metadata": {"token": "nope"},
    }
    row = build_user_row(device, counts={"approved": 2, "proposed": 1}, last_memory_at=None)
    blob = str(row)
    assert "secret-hash" not in blob
    assert "token" not in blob
    assert row["app_version"] == "Unknown"
    assert row["language"] == "Unknown"
    assert row["machine_model"] == "Unknown"
    assert row["personality"] == "Default"
    assert row["memory_connected"] is False
    assert row["memory_health"] == "never_connected"
    assert row["approved_memory_count"] == 2
    assert row["proposed_memory_count"] == 1


# --- list/detail helpers ----------------------------------------------------------


def test_list_and_detail_counts_and_memory_flags(stores):
    devices, memory = stores
    now = datetime.now(timezone.utc)

    async def _run():
        d1 = await _create_device(devices, "install-aaaaaaaaaaaa", last_seen=now.isoformat())
        d2 = await _create_device(
            devices,
            "install-bbbbbbbbbbbb",
            last_seen=(now - timedelta(days=10)).isoformat(),
        )
        await _create_device(devices, "install-cccccccccccc", revoked=True)
        await memory.create_memory(d1["id"], {"type": "preference", "content": "x", "status": "approved"})
        await memory.create_memory(d1["id"], {"type": "preference", "content": "y", "status": "proposed"})
        await memory.write_audit({
            "device_id": d1["id"],
            "action": "context",
            "created_at": now.isoformat(),
            "request_id": "r1",
            "actor": "device",
            "details": {"secret": "no"},
        })
        listing = await list_admin_users(
            device_store=devices, memory_store=memory, page=1, page_size=25, now=now,
        )
        detail = await get_admin_user_detail(
            device_store=devices, memory_store=memory, device_id=d1["id"], now=now,
        )
        never = await get_admin_user_detail(
            device_store=devices, memory_store=memory, device_id=d2["id"], now=now,
        )
        return listing, detail, never, d1["id"], d2["id"]

    listing, detail, never, id1, id2 = asyncio.run(_run())
    assert listing["total"] == 2
    by_id = {u["device_id"]: u for u in listing["users"]}
    assert by_id[id1]["memory_connected"] is True
    assert by_id[id1]["memory_health"] == "healthy"
    assert by_id[id1]["approved_memory_count"] == 1
    assert by_id[id1]["proposed_memory_count"] == 1
    assert by_id[id2]["memory_connected"] is False
    assert by_id[id2]["memory_health"] == "never_connected"
    assert listing["users"][0]["device_id"] == id1  # newest active first
    assert detail is not None
    assert detail["recent_action_flows"] == []
    assert detail["personality"] == "Default"
    assert detail["last_memory_response_ms"] is None
    assert never is not None
    assert never["memory_health"] == "never_connected"
    blob = str(listing) + str(detail)
    assert "secret" not in blob
    assert "token_hash" not in blob
    assert "request_id" not in blob


def test_pagination_search_filters(stores):
    devices, memory = stores
    now = datetime.now(timezone.utc)

    async def _run():
        rows = []
        for i in range(5):
            rows.append(await _create_device(
                devices,
                f"install-{i:016d}",
                app_version="1.4.12" if i % 2 == 0 else "1.4.10",
                last_seen=(now - timedelta(minutes=i)).isoformat(),
                metadata={"language": "en" if i < 3 else "he", "machine_model": "CoffeeAI"},
            ))
        await memory.write_audit({
            "device_id": rows[0]["id"],
            "action": "candidates_submit",
            "created_at": now.isoformat(),
            "request_id": "r",
            "actor": "device",
            "details": {},
        })
        page1 = await list_admin_users(
            device_store=devices, memory_store=memory, page=1, page_size=2, now=now,
        )
        page2 = await list_admin_users(
            device_store=devices, memory_store=memory, page=2, page_size=2, now=now,
        )
        search = await list_admin_users(
            device_store=devices, memory_store=memory, search="1.4.10", now=now,
        )
        connected = await list_admin_users(
            device_store=devices, memory_store=memory, memory="connected", now=now,
        )
        not_connected = await list_admin_users(
            device_store=devices, memory_store=memory, memory="not_connected", now=now,
        )
        return page1, page2, search, connected, not_connected

    page1, page2, search, connected, not_connected = asyncio.run(_run())
    assert page1["page"] == 1 and page1["page_size"] == 2 and page1["total"] == 5
    assert len(page1["users"]) == 2
    assert len(page2["users"]) == 2
    assert all(u["app_version"] == "1.4.10" for u in search["users"])
    assert connected["total"] == 1
    assert not_connected["total"] == 4


def test_partial_audit_failure_safe_fallback(stores):
    devices, _ = stores

    async def _run():
        await _create_device(devices, "install-dddddddddddd")
        return await list_admin_users(
            device_store=devices,
            memory_store=FailingAuditMemoryStore(),
            page=1,
            page_size=25,
        )

    listing = asyncio.run(_run())
    assert listing["total"] == 1
    assert listing["users"][0]["memory_connected"] is False
    assert listing["users"][0]["approved_memory_count"] == 0


def test_no_writes_on_list(stores):
    devices, memory = FailingDeviceStore(), FailingAuditMemoryStore()
    # device list fails -> empty; must not write
    listing = asyncio.run(list_admin_users(device_store=devices, memory_store=memory))
    assert listing["total"] == 0
    assert memory.audit == []
    assert memory.memories == {}


# --- HTTP -------------------------------------------------------------------------


def test_users_unauthenticated_401(client):
    assert client.get("/admin/api/users").status_code == 401
    assert client.get("/admin/api/users/aaaaaaaa-bbbb-cccc-dddd-eeeeeeee1048").status_code == 401


def test_users_list_and_detail_http(client, stores):
    devices, memory = stores
    now = datetime.now(timezone.utc)

    async def _seed():
        d = await _create_device(devices, "install-eeeeeeeeeeee", last_seen=now.isoformat())
        await memory.create_memory(d["id"], {"type": "preference", "content": "x", "status": "approved"})
        await memory.write_audit({
            "device_id": d["id"],
            "action": "context",
            "created_at": now.isoformat(),
            "request_id": "r",
            "actor": "device",
            "details": {},
        })
        return d["id"]

    device_id = asyncio.run(_seed())
    _login(client)
    r = client.get("/admin/api/users?page=1&page_size=25")
    assert r.status_code == 200
    body = r.json()
    assert body["total"] == 1
    assert body["page"] == 1
    assert body["page_size"] == 25
    user = body["users"][0]
    assert user["device_id"] == device_id
    assert "token" not in str(body).lower() or "token_hash" not in str(body)
    assert "token_hash" not in str(body)
    assert "metadata" not in str(body)
    assert user["personality"] == "Default"
    assert user["memory_connected"] is True

    detail = client.get(f"/admin/api/users/{device_id}")
    assert detail.status_code == 200
    d = detail.json()
    assert d["device_id"] == device_id
    assert d["recent_action_flows"] == []
    assert d["approved_memory_count"] == 1
    assert "token_hash" not in str(d)

    assert client.get("/admin/api/users/00000000-0000-0000-0000-000000000000").status_code == 404


def test_page_size_maximum_enforced(client, stores):
    devices, _memory = stores
    asyncio.run(_create_device(devices, "install-ffffffffffff"))
    _login(client)
    r = client.get(f"/admin/api/users?page_size={MAX_PAGE_SIZE + 50}")
    assert r.status_code == 200
    assert r.json()["page_size"] == MAX_PAGE_SIZE


def test_users_rejects_api_key_without_session(client, monkeypatch):
    monkeypatch.setattr(settings, "API_KEY", "test-shared-key")
    r = client.get("/admin/api/users", headers={"X-API-Key": "test-shared-key"})
    assert r.status_code == 401


def test_users_shell_has_table_ui(client):
    _login(client)
    r = client.get("/admin/users")
    assert r.status_code == 200
    assert 'id="usersRoot"' in r.text
    assert "User / Device" in r.text
    assert "This section will be built in the next stage." in r.text  # still in DOM, hidden by JS


def test_overview_still_works(client, stores):
    _login(client)
    r = client.get("/admin/api/overview")
    assert r.status_code == 200
    assert r.json()["backend"]["status"] == "online"


def test_android_version_unchanged():
    gradle = (Path(__file__).resolve().parents[2] / "android" / "app" / "build.gradle.kts").read_text(
        encoding="utf-8"
    )
    assert 'versionName = "1.4.12"' in gradle
