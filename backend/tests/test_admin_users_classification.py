"""Real vs test device classification, corrected activity/status, and real-only counting."""
from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone

from admin_overview import build_overview_payload
from admin_users import (
    build_user_row,
    classify_device_type,
    genuine_last_active,
    list_admin_users,
)
from devices import InMemoryDeviceStore
from memory_store import InMemoryMemoryStore

NOW = datetime(2026, 7, 9, 12, 0, 0, tzinfo=timezone.utc)


def _iso(dt: datetime) -> str:
    return dt.isoformat()


def _device(install_id: str, *, created=NOW, last_seen=None, revoked=False) -> dict:
    row = {
        "id": "dddddddd-eeee-ffff-0000-111122223333",
        "install_id": install_id,
        "token_hash": "x" * 64,
        "app_version": "1.4.12",
        "created_at": _iso(created),
        "last_seen_at": _iso(last_seen) if last_seen else _iso(created),
        "revoked_at": _iso(NOW) if revoked else None,
        "metadata": {},
    }
    return row


# --- classification ---------------------------------------------------------------


def test_classify_real_test_unknown():
    assert classify_device_type("coffee-abc123") == "real"
    assert classify_device_type("COFFEE-ABC") == "real"
    for marker in ("stage-1", "smoke-test", "verification-x", "controlled-run", "temp-1", "admin-check"):
        assert classify_device_type(marker) == "test"
    assert classify_device_type("random-id-42") == "unknown"
    assert classify_device_type("") == "unknown"
    assert classify_device_type(None) == "unknown"


# --- genuine activity (no created_at fallback) ------------------------------------


def test_genuine_last_active_ignores_created_and_registration_touch():
    created = NOW
    # last_seen within 5s of created == the registration touch, not real activity
    assert genuine_last_active(_iso(created), _iso(created + timedelta(seconds=2)), None) is None
    # last_seen well after created counts
    assert genuine_last_active(_iso(created), _iso(created + timedelta(minutes=10)), None) is not None
    # memory activity always counts
    assert genuine_last_active(_iso(created), _iso(created), _iso(created)) == _iso(created)


# --- status matrix ----------------------------------------------------------------


def _row(install_id, *, last_seen=None, last_memory_at=None, created=NOW, revoked=False):
    return build_user_row(
        _device(install_id, created=created, last_seen=last_seen, revoked=revoked),
        counts={},
        last_memory_at=last_memory_at,
        now=NOW,
    )


def test_status_registered_only_for_real_without_activity():
    r = _row("coffee-1")
    assert r["device_type"] == "real"
    assert r["backend_status"] == "registered_only"
    assert r["status"] == "registered_only"
    assert r["last_active"] is None
    assert r["last_real_activity"] is None


def test_status_active_for_recent_last_seen():
    r = _row("coffee-2", created=NOW - timedelta(hours=2), last_seen=NOW)
    assert r["backend_status"] == "active"
    assert r["status"] == "active"
    assert r["last_real_activity"] is not None


def test_status_memory_active_for_recent_memory():
    r = _row("coffee-3", last_memory_at=_iso(NOW))
    assert r["memory_connected"] is True
    assert r["memory_status"] == "connected"
    assert r["status"] == "memory_active"
    assert r["backend_status"] == "active"


def test_status_offline_for_old_activity():
    old = NOW - timedelta(days=10)
    r = _row("coffee-4", created=old - timedelta(hours=1), last_seen=old)
    assert r["backend_status"] == "offline"
    assert r["status"] == "offline"


def test_test_and_unknown_devices_flagged():
    t = _row("stage-run-1", last_memory_at=_iso(NOW))
    assert t["device_type"] == "test"
    assert t["status"] == "test"
    assert t["backend_status"] == "test"
    u = _row("some-random-id")
    assert u["device_type"] == "unknown"
    assert u["status"] == "unknown"
    assert u["backend_status"] == "unknown"


# --- list type filter -------------------------------------------------------------


def _seed(store: InMemoryDeviceStore):
    async def _run():
        real = await store.create_device("coffee-real-1", "c" * 64, "android", "1.4.12")
        test = await store.create_device("stage-smoke-1", "c" * 64, "android", "1.4.12")
        unknown = await store.create_device("weird-999", "c" * 64, "android", "1.4.12")
        return real["id"], test["id"], unknown["id"]

    return asyncio.run(_run())


def test_default_list_hides_test_devices():
    devices, memory = InMemoryDeviceStore(), InMemoryMemoryStore()
    real_id, test_id, unknown_id = _seed(devices)
    listing = asyncio.run(
        list_admin_users(device_store=devices, memory_store=memory, now=NOW)
    )
    ids = {u["device_id"] for u in listing["users"]}
    assert test_id not in ids
    assert real_id in ids
    assert unknown_id in ids
    assert listing["total"] == 2


def test_type_filter_selects_single_class():
    devices, memory = InMemoryDeviceStore(), InMemoryMemoryStore()
    real_id, test_id, unknown_id = _seed(devices)

    async def _run():
        only_real = await list_admin_users(
            device_store=devices, memory_store=memory, type_filter="real", now=NOW
        )
        only_test = await list_admin_users(
            device_store=devices, memory_store=memory, type_filter="test", now=NOW
        )
        all_types = await list_admin_users(
            device_store=devices, memory_store=memory, type_filter="all", now=NOW
        )
        return only_real, only_test, all_types

    only_real, only_test, all_types = asyncio.run(_run())
    assert [u["device_id"] for u in only_real["users"]] == [real_id]
    assert [u["device_id"] for u in only_test["users"]] == [test_id]
    assert all_types["total"] == 3


# --- overview counts real non-revoked only ----------------------------------------


def test_overview_counts_real_non_revoked_only():
    devices, memory = InMemoryDeviceStore(), InMemoryMemoryStore()

    async def _run():
        await devices.create_device("coffee-real-a", "c" * 64, "android", "1.4.12")
        revoked = await devices.create_device("coffee-real-b", "c" * 64, "android", "1.4.12")
        devices._by_id[revoked["id"]]["revoked_at"] = _iso(NOW)
        await devices.create_device("stage-test-x", "c" * 64, "android", "1.4.12")
        await devices.create_device("plain-unknown", "c" * 64, "android", "1.4.12")
        return await build_overview_payload(
            device_store=devices, memory_store=memory, hermes_import_ok=True
        )

    payload = asyncio.run(_run())
    assert payload["connected_users"]["count"] == 1
