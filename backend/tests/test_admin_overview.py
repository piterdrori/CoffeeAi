"""Stage 6A-3: Control Center Overview read API tests."""
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
from admin_overview import (
    ACTIVITY_LIMIT,
    build_overview_payload,
    derive_hermes_status,
    map_audit_to_activity,
)
from config import settings
from devices import DeviceStoreUnavailable, InMemoryDeviceStore
from hermes import HERMES_VERSION
from memory_store import InMemoryMemoryStore, MemoryStoreUnavailable

WEB_DIR = Path(__file__).resolve().parents[1] / "web"
CONTROL_DIR = WEB_DIR / "control"


class FailingCountDeviceStore(InMemoryDeviceStore):
    async def count_connected_devices(self) -> int:
        raise DeviceStoreUnavailable("simulated")

    async def health(self) -> dict[str, bool | str]:
        return {"readable": False, "writable": False}


class FailingAuditMemoryStore(InMemoryMemoryStore):
    async def list_recent_audit_actions(self, *, limit: int = 40) -> list[dict[str, Any]]:
        raise MemoryStoreUnavailable("simulated")

    async def count_memory_proposals(self) -> int:
        raise MemoryStoreUnavailable("simulated")

    async def write_audit(self, entry):
        raise AssertionError("overview must not write audit")

    async def create_memory(self, device_id, data):
        raise AssertionError("overview must not write memory")


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
    return client.post("/admin/api/login", json={"password": "test-admin-password-ok"})


async def _create_device(store: InMemoryDeviceStore, install: str, *, revoked: bool = False):
    row = await store.create_device(install, "b" * 64, "android", "1.4.12")
    if revoked:
        store._by_id[row["id"]]["revoked_at"] = datetime.now(timezone.utc).isoformat()
    return row


def test_map_activity_excludes_unknown_and_limits():
    now = datetime.now(timezone.utc)
    rows = []
    for i in range(12):
        rows.append({
            "action": "candidates_submit",
            "created_at": (now - timedelta(minutes=i)).isoformat(),
        })
    rows.insert(0, {"action": "context", "created_at": now.isoformat()})
    rows.append({"action": "secret_dump", "created_at": now.isoformat(), "details": {"token": "x"}})
    items = map_audit_to_activity(rows, limit=ACTIVITY_LIMIT)
    assert len(items) == ACTIVITY_LIMIT
    assert all(i["label"] == "Memory proposed" for i in items)
    assert all(set(i.keys()) == {"type", "label", "timestamp"} for i in items)
    stamps = [i["timestamp"] for i in items]
    assert stamps == sorted(stamps, reverse=True)


def test_map_activity_labels():
    now = datetime.now(timezone.utc).isoformat()
    items = map_audit_to_activity([
        {"action": "device_register", "created_at": now},
        {"action": "memory_approve", "created_at": now},
        {"action": "knowledge_ingest", "created_at": now},
        {"action": "video_upload", "created_at": now},
        {"action": "action_flow_triggered", "created_at": now},
        {"action": "action_flow_failed", "created_at": now},
        {"action": "hermes_warning", "created_at": now},
    ])
    assert [i["label"] for i in items] == [
        "User connected",
        "Memory approved",
        "Document uploaded",
        "Video uploaded",
        "Action Flow triggered",
        "Action Flow failed",
        "Hermes warning",
    ]


def test_hermes_active_with_recent_success():
    now = datetime.now(timezone.utc)
    h = derive_hermes_status(
        hermes_import_ok=True,
        backend_online=True,
        audit_rows=[{"action": "context", "created_at": now.isoformat()}],
        now=now,
    )
    assert h["status"] == "active"
    assert h["version"] == HERMES_VERSION
    assert h["last_activity"]


def test_hermes_warning_on_failure_or_no_success():
    now = datetime.now(timezone.utc)
    warn = derive_hermes_status(
        hermes_import_ok=True,
        backend_online=True,
        audit_rows=[{"action": "hermes_warning", "created_at": now.isoformat()}],
        now=now,
    )
    assert warn["status"] == "warning"
    empty = derive_hermes_status(
        hermes_import_ok=True,
        backend_online=True,
        audit_rows=[],
        now=now,
    )
    assert empty["status"] == "warning"


def test_hermes_offline_when_import_fails():
    h = derive_hermes_status(hermes_import_ok=False, backend_online=True, audit_rows=[])
    assert h["status"] == "offline"


def test_build_overview_counts_and_action_flows(stores):
    devices, memory = stores

    async def _run():
        await _create_device(devices, "coffee-a1")
        await _create_device(devices, "coffee-a2")
        await _create_device(devices, "coffee-a3", revoked=True)
        await memory.create_memory("d1", {"type": "preference", "content": "x", "status": "proposed"})
        await memory.create_memory("d1", {"type": "preference", "content": "y", "status": "approved"})
        now = datetime.now(timezone.utc)
        await memory.write_audit({
            "action": "context",
            "created_at": now.isoformat(),
            "device_id": "d1",
            "request_id": "r1",
            "actor": "device",
            "details": {"secret": "nope"},
        })
        await memory.write_audit({
            "action": "candidates_submit",
            "created_at": (now - timedelta(seconds=1)).isoformat(),
            "device_id": "d1",
            "request_id": "r2",
            "actor": "device",
            "details": {"memory_id": "m1"},
        })
        return await build_overview_payload(
            device_store=devices,
            memory_store=memory,
            hermes_import_ok=True,
        )

    payload = asyncio.run(_run())
    assert payload["backend"]["status"] == "online"
    assert payload["database"]["status"] == "connected"
    assert payload["hermes"]["status"] == "active"
    assert payload["hermes"]["version"] == HERMES_VERSION
    assert payload["connected_users"]["count"] == 2
    assert payload["memory_proposals"]["count"] == 1
    assert payload["active_action_flows"] == {"count": 0, "configured": False}
    assert len(payload["recent_activity"]) == 1
    assert payload["recent_activity"][0]["label"] == "Memory proposed"
    blob = str(payload)
    assert "secret" not in blob
    assert "memory_id" not in blob
    assert "request_id" not in blob
    assert "d1" not in blob


def test_database_failure_maps_unavailable():
    payload = asyncio.run(build_overview_payload(
        device_store=FailingCountDeviceStore(),
        memory_store=InMemoryMemoryStore(),
        hermes_import_ok=True,
    ))
    assert payload["database"]["status"] == "unavailable"
    assert payload["connected_users"]["count"] == 0
    assert payload["backend"]["status"] == "online"


def test_partial_store_failure_does_not_crash():
    devices = InMemoryDeviceStore()

    async def _run():
        await _create_device(devices, "coffee-ok")
        return await build_overview_payload(
            device_store=devices,
            memory_store=FailingAuditMemoryStore(),
            hermes_import_ok=True,
        )

    payload = asyncio.run(_run())
    assert payload["backend"]["status"] == "online"
    assert payload["connected_users"]["count"] == 1
    assert payload["memory_proposals"]["count"] == 0
    assert payload["recent_activity"] == []
    assert payload["hermes"]["status"] == "warning"


def test_overview_performs_no_memory_writes():
    devices = InMemoryDeviceStore()
    memory = FailingAuditMemoryStore()
    before = len(memory.memories)
    asyncio.run(build_overview_payload(
        device_store=devices, memory_store=memory, hermes_import_ok=True,
    ))
    assert len(memory.memories) == before
    assert memory.audit == []


def test_overview_unauthenticated_401(client):
    r = client.get("/admin/api/overview")
    assert r.status_code == 401
    assert r.json()["detail"] == "admin_auth_required"


def test_overview_authenticated_200(client, stores):
    devices, _memory = stores
    asyncio.run(_create_device(devices, "coffee-u1"))
    assert _login(client).status_code == 200
    r = client.get("/admin/api/overview")
    assert r.status_code == 200
    body = r.json()
    assert body["backend"]["status"] == "online"
    assert body["database"]["status"] == "connected"
    assert body["hermes"]["version"] == HERMES_VERSION
    assert body["connected_users"]["count"] == 1
    assert body["active_action_flows"]["configured"] is False
    assert body["active_action_flows"]["count"] == 0
    assert isinstance(body["recent_activity"], list)
    assert len(body["recent_activity"]) <= 8
    text = r.text
    assert "API_KEY" not in text
    assert "SUPABASE" not in text
    assert "service_role" not in text
    assert "localStorage" not in text
    assert settings.API_KEY not in text
    assert settings.CONTROL_CENTER_PASSWORD not in text


def test_overview_rejects_api_key_without_session(client, monkeypatch):
    monkeypatch.setattr(settings, "API_KEY", "test-shared-key")
    r = client.get("/admin/api/overview", headers={"X-API-Key": "test-shared-key"})
    assert r.status_code == 401


def test_activity_sorted_and_capped_http(client, stores):
    _devices, memory = stores
    now = datetime.now(timezone.utc)
    for i in range(10):
        memory.audit.append({
            "action": "memory_approve",
            "created_at": (now - timedelta(minutes=i)).isoformat(),
            "device_id": f"dev-{i}",
            "request_id": f"req-{i}",
            "details": {"memory_id": f"m-{i}"},
        })
    memory.audit.append({
        "action": "unknown_internal",
        "created_at": now.isoformat(),
        "details": {"x": 1},
    })
    assert _login(client).status_code == 200
    body = client.get("/admin/api/overview").json()
    acts = body["recent_activity"]
    assert len(acts) == 8
    assert [a["timestamp"] for a in acts] == sorted((a["timestamp"] for a in acts), reverse=True)
    assert all(a["label"] == "Memory approved" for a in acts)
    assert "dev-" not in str(body)
    assert "req-" not in str(body)
    assert "memory_id" not in str(body)


def test_unified_root_and_health_still_ok(client):
    assert client.get("/").status_code == 303
    assert client.get("/health").json()["status"] == "ok"


def test_android_version_unchanged():
    gradle = (Path(__file__).resolve().parents[2] / "android" / "app" / "build.gradle.kts").read_text(
        encoding="utf-8"
    )
    assert 'versionName = "1.4.12"' in gradle


def test_no_overview_ui_cards_in_shell():
    # Stage 6A-4: Overview markup is intentional; still must not embed secrets or API keys.
    shell = (CONTROL_DIR / "shell.html").read_text(encoding="utf-8")
    assert 'id="overviewRoot"' in shell
    assert "API_KEY" not in shell
    assert "localStorage" not in shell
    assert "service_role" not in shell
