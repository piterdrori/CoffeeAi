"""Stage 6A-3: privacy-safe Control Center Overview read API helpers.

Pure assembly of store counts + sanitized audit labels. No LLM, no embeddings,
no memory writes, no secrets in responses.
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
from typing import Any

from devices import DeviceStore, DeviceStoreUnavailable
from hermes import HERMES_VERSION
from memory_store import MemoryStore, MemoryStoreUnavailable

ACTIVITY_LIMIT = 8
HERMES_ACTIVITY_WINDOW = timedelta(days=7)

# Audit action → Overview label. Unmapped actions are excluded.
ACTIVITY_LABELS: dict[str, str] = {
    "device_register": "User connected",
    "device_connected": "User connected",
    "user_connected": "User connected",
    "candidates_submit": "Memory proposed",
    "memory_create": "Memory proposed",
    "memory_consolidate": "Memory proposed",
    "memory_approve": "Memory approved",
    "knowledge_ingest": "Document uploaded",
    "video_upload": "Video uploaded",
    "video_uploaded": "Video uploaded",
    "action_flow_triggered": "Action Flow triggered",
    "action_flow_failed": "Action Flow failed",
    "hermes_warning": "Hermes warning",
}

HERMES_SUCCESS_ACTIONS = frozenset({
    "context",
    "candidates_submit",
    "candidates_idempotent_replay",
    "memory_consolidate",
    "memory_approve",
    "memory_create",
})
HERMES_FAILURE_ACTIONS = frozenset({
    "hermes_warning",
    "hermes_error",
    "context_failed",
})


def _parse_ts(value: str | None) -> datetime | None:
    if not value or not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def map_audit_to_activity(rows: list[dict[str, Any]], *, limit: int = ACTIVITY_LIMIT) -> list[dict[str, str]]:
    """Map sanitized audit rows to Overview activity items. Newest-first, max `limit`."""
    items: list[dict[str, str]] = []
    for row in rows:
        action = row.get("action")
        created = row.get("created_at")
        if not isinstance(action, str) or not isinstance(created, str):
            continue
        label = ACTIVITY_LABELS.get(action)
        if not label:
            continue
        items.append({
            "type": action,
            "label": label,
            "timestamp": created,
        })
        if len(items) >= limit:
            break
    return items


def derive_hermes_status(
    *,
    hermes_import_ok: bool,
    backend_online: bool,
    audit_rows: list[dict[str, Any]],
    now: datetime | None = None,
) -> dict[str, Any]:
    """Library-based Hermes status — no fake process heartbeat."""
    version = HERMES_VERSION if hermes_import_ok else None
    last_activity: str | None = None
    recent_failure = False
    recent_success = False
    now = now or datetime.now(timezone.utc)
    window_start = now - HERMES_ACTIVITY_WINDOW

    for row in audit_rows:
        action = row.get("action")
        created = row.get("created_at")
        if not isinstance(action, str) or not isinstance(created, str):
            continue
        ts = _parse_ts(created)
        if ts is None:
            continue
        if ts.tzinfo is None:
            ts = ts.replace(tzinfo=timezone.utc)
        if action in HERMES_SUCCESS_ACTIONS or action in HERMES_FAILURE_ACTIONS:
            if last_activity is None or created > last_activity:
                last_activity = created
        if ts < window_start:
            continue
        if action in HERMES_FAILURE_ACTIONS:
            recent_failure = True
        if action in HERMES_SUCCESS_ACTIONS:
            recent_success = True

    if not hermes_import_ok or not backend_online:
        status = "offline"
    elif recent_failure or not recent_success:
        # Failure signal, or no successful Hermes activity in the window → warning
        status = "warning"
    else:
        status = "active"

    return {
        "status": status,
        "version": version or HERMES_VERSION,
        "last_activity": last_activity,
    }


async def _safe_device_health(store: DeviceStore) -> dict[str, bool | str]:
    try:
        return await store.health()
    except Exception:  # noqa: BLE001
        return {"readable": False, "writable": False}


async def _safe_count_devices(store: DeviceStore) -> int | None:
    try:
        return int(await store.count_connected_devices())
    except (DeviceStoreUnavailable, Exception):  # noqa: BLE001
        return None


async def _safe_count_proposals(store: MemoryStore) -> int | None:
    try:
        return int(await store.count_memory_proposals())
    except (MemoryStoreUnavailable, Exception):  # noqa: BLE001
        return None


async def _safe_list_audit(store: MemoryStore, *, limit: int = 40) -> list[dict[str, Any]] | None:
    try:
        return await store.list_recent_audit_actions(limit=limit)
    except (MemoryStoreUnavailable, Exception):  # noqa: BLE001
        return None


def _probe_hermes_import() -> bool:
    try:
        from hermes import HERMES_VERSION as _v  # noqa: F401

        return bool(_v)
    except Exception:  # noqa: BLE001
        return False


async def build_overview_payload(
    *,
    device_store: DeviceStore,
    memory_store: MemoryStore,
    hermes_import_ok: bool | None = None,
) -> dict[str, Any]:
    """Assemble Overview JSON. Never raises for partial store failures."""
    backend_online = True
    if hermes_import_ok is None:
        hermes_import_ok = _probe_hermes_import()

    health_task = asyncio.create_task(_safe_device_health(device_store))
    users_task = asyncio.create_task(_safe_count_devices(device_store))
    proposals_task = asyncio.create_task(_safe_count_proposals(memory_store))
    audit_task = asyncio.create_task(_safe_list_audit(memory_store, limit=40))

    health, users, proposals, audit = await asyncio.gather(
        health_task, users_task, proposals_task, audit_task,
    )

    readable = bool(health.get("readable"))
    writable = health.get("writable")
    db_connected = readable and writable is True

    audit_rows = audit if isinstance(audit, list) else []
    hermes = derive_hermes_status(
        hermes_import_ok=bool(hermes_import_ok),
        backend_online=backend_online,
        audit_rows=audit_rows,
    )
    if audit is None:
        # Cannot confirm recent success → warning (not offline)
        hermes["status"] = "warning" if hermes_import_ok and backend_online else hermes["status"]

    activity = map_audit_to_activity(audit_rows, limit=ACTIVITY_LIMIT) if audit is not None else []

    return {
        "backend": {"status": "online" if backend_online else "offline"},
        "database": {"status": "connected" if db_connected else "unavailable"},
        "hermes": hermes,
        "connected_users": {"count": users if users is not None else 0},
        "memory_proposals": {"count": proposals if proposals is not None else 0},
        "active_action_flows": {"count": 0, "configured": False},
        "recent_activity": activity,
    }
