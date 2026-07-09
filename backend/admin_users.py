"""Stage 6B-1: privacy-safe Control Center Users read API helpers.

Read-only assembly of device rows + memory counts + audit activity.
No LLM, embeddings, writes, or secrets in responses.

Latency for "slow" memory health is not stored today — only
healthy / offline / never_connected are derived.
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
from typing import Any

from devices import DeviceStore, DeviceStoreUnavailable
from memory_store import MemoryStore, MemoryStoreUnavailable

ACTIVITY_WINDOW = timedelta(days=7)
DEFAULT_PAGE_SIZE = 25
MAX_PAGE_SIZE = 100
DEFAULT_PERSONALITY = "Default"
UNKNOWN = "Unknown"

MEMORY_SUCCESS_ACTIONS = frozenset({
    "context",
    "candidates_submit",
    "candidates_idempotent_replay",
    "memory_create",
    "memory_approve",
    "memory_consolidate",
    "summary_upsert",
})


def parse_ts(value: str | None) -> datetime | None:
    if not value or not isinstance(value, str):
        return None
    try:
        ts = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if ts.tzinfo is None:
        ts = ts.replace(tzinfo=timezone.utc)
    return ts


def display_name_for(device_id: str, metadata: dict[str, Any] | None = None) -> str:
    meta = metadata if isinstance(metadata, dict) else {}
    for key in ("display_name", "name", "label"):
        raw = meta.get(key)
        if isinstance(raw, str) and raw.strip():
            return raw.strip()[:64]
    suffix = (device_id or "").replace("-", "")[-4:] or "----"
    return f"Device {suffix}"


def safe_app_version(row: dict[str, Any]) -> str:
    value = row.get("app_version")
    if isinstance(value, str) and value.strip():
        return value.strip()[:32]
    return UNKNOWN


def safe_language(row: dict[str, Any]) -> str:
    meta = row.get("metadata") if isinstance(row.get("metadata"), dict) else {}
    for key in ("language", "locale", "lang"):
        value = meta.get(key) if isinstance(meta, dict) else None
        if isinstance(value, str) and value.strip():
            return value.strip()[:16]
    return UNKNOWN


def safe_machine_model(row: dict[str, Any]) -> str:
    meta = row.get("metadata") if isinstance(row.get("metadata"), dict) else {}
    for key in ("machine_model", "product", "model", "machine"):
        value = meta.get(key) if isinstance(meta, dict) else None
        if isinstance(value, str) and value.strip():
            return value.strip()[:64]
    return UNKNOWN


def safe_personality(row: dict[str, Any]) -> str:
    meta = row.get("metadata") if isinstance(row.get("metadata"), dict) else {}
    value = meta.get("personality") if isinstance(meta, dict) else None
    if isinstance(value, str) and value.strip():
        return value.strip()[:64]
    return DEFAULT_PERSONALITY


def derive_memory_health(
    *,
    last_memory_at: str | None,
    now: datetime | None = None,
) -> str:
    """Latency is not stored — never fabricate 'slow'."""
    now = now or datetime.now(timezone.utc)
    if not last_memory_at:
        return "never_connected"
    ts = parse_ts(last_memory_at)
    if ts is None:
        return "never_connected"
    if now - ts <= ACTIVITY_WINDOW:
        return "healthy"
    return "offline"


def derive_status(
    *,
    last_active: str | None,
    memory_health: str,
    now: datetime | None = None,
) -> str:
    now = now or datetime.now(timezone.utc)
    if memory_health == "slow":
        return "needs_attention"
    ts = parse_ts(last_active)
    if ts is None:
        return "offline"
    if now - ts <= ACTIVITY_WINDOW:
        return "active"
    return "offline"


def _max_iso(*values: str | None) -> str | None:
    best: str | None = None
    for value in values:
        if not isinstance(value, str) or not value:
            continue
        if best is None or value > best:
            best = value
    return best


def build_user_row(
    device: dict[str, Any],
    *,
    counts: dict[str, int] | None,
    last_memory_at: str | None,
    now: datetime | None = None,
    detail: bool = False,
) -> dict[str, Any]:
    now = now or datetime.now(timezone.utc)
    device_id = str(device.get("id") or "")
    meta = device.get("metadata") if isinstance(device.get("metadata"), dict) else {}
    first = device.get("created_at") if isinstance(device.get("created_at"), str) else None
    last_seen = device.get("last_seen_at") if isinstance(device.get("last_seen_at"), str) else None
    last_active = _max_iso(last_seen, last_memory_at, first)
    memory_connected = bool(last_memory_at)
    memory_health = derive_memory_health(last_memory_at=last_memory_at, now=now)
    status = derive_status(last_active=last_active, memory_health=memory_health, now=now)
    counts = counts or {}
    row: dict[str, Any] = {
        "device_id": device_id,
        "display_name": display_name_for(device_id, meta),
        "first_connected": first,
        "last_active": last_active,
        "app_version": safe_app_version(device),
        "language": safe_language(device),
        "machine_model": safe_machine_model(device),
        "memory_connected": memory_connected,
        "memory_health": memory_health,
        "personality": safe_personality(device),
        "approved_memory_count": int(counts.get("approved") or 0),
        "proposed_memory_count": int(counts.get("proposed") or 0),
        "status": status,
    }
    if detail:
        row["last_memory_connection"] = last_memory_at
        # Latency not stored — return null rather than inventing a value.
        row["last_memory_response_ms"] = None
        row["last_memory_error"] = None
        row["recent_action_flows"] = []
        # list contract keeps status; detail also keeps it for consistency
    return row


def _matches_search(row: dict[str, Any], search: str) -> bool:
    q = search.strip().lower()
    if not q:
        return True
    haystacks = [
        row.get("display_name"),
        row.get("device_id"),
        row.get("app_version"),
        row.get("language"),
        row.get("machine_model"),
    ]
    return any(isinstance(h, str) and q in h.lower() for h in haystacks)


def _sort_key(row: dict[str, Any]) -> tuple[int, str]:
    last = row.get("last_active")
    if isinstance(last, str) and last:
        return (0, last)
    return (1, "")


async def _safe_counts(store: MemoryStore) -> dict[str, dict[str, int]]:
    try:
        return await store.grouped_memory_status_counts()
    except (MemoryStoreUnavailable, Exception):  # noqa: BLE001
        return {}


async def _safe_activity(store: MemoryStore) -> dict[str, str]:
    try:
        return await store.latest_memory_activity_by_device()
    except (MemoryStoreUnavailable, Exception):  # noqa: BLE001
        return {}


async def list_admin_users(
    *,
    device_store: DeviceStore,
    memory_store: MemoryStore,
    page: int = 1,
    page_size: int = DEFAULT_PAGE_SIZE,
    search: str | None = None,
    memory: str | None = None,
    status: str | None = None,
    now: datetime | None = None,
) -> dict[str, Any]:
    """Paginated privacy-safe users list. Read-only."""
    now = now or datetime.now(timezone.utc)
    page = max(1, int(page or 1))
    page_size = max(1, min(int(page_size or DEFAULT_PAGE_SIZE), MAX_PAGE_SIZE))

    try:
        devices = await device_store.list_active_devices()
    except (DeviceStoreUnavailable, Exception):  # noqa: BLE001
        return {
            "users": [],
            "total": 0,
            "page": page,
            "page_size": page_size,
        }

    counts_task = asyncio.create_task(_safe_counts(memory_store))
    activity_task = asyncio.create_task(_safe_activity(memory_store))
    counts, activity = await asyncio.gather(counts_task, activity_task)

    rows: list[dict[str, Any]] = []
    for device in devices:
        if device.get("revoked_at") is not None:
            continue
        device_id = str(device.get("id") or "")
        if not device_id:
            continue
        row = build_user_row(
            device,
            counts=counts.get(device_id),
            last_memory_at=activity.get(device_id),
            now=now,
            detail=False,
        )
        rows.append(row)

    if search:
        rows = [r for r in rows if _matches_search(r, search)]
    if memory == "connected":
        rows = [r for r in rows if r.get("memory_connected") is True]
    elif memory == "not_connected":
        rows = [r for r in rows if r.get("memory_connected") is False]
    if status in ("active", "needs_attention", "offline"):
        rows = [r for r in rows if r.get("status") == status]

    rows.sort(key=_sort_key, reverse=True)
    total = len(rows)
    start = (page - 1) * page_size
    end = start + page_size
    return {
        "users": rows[start:end],
        "total": total,
        "page": page,
        "page_size": page_size,
    }


async def get_admin_user_detail(
    *,
    device_store: DeviceStore,
    memory_store: MemoryStore,
    device_id: str,
    now: datetime | None = None,
) -> dict[str, Any] | None:
    """Single-user privacy-safe detail. Returns None if missing/revoked."""
    now = now or datetime.now(timezone.utc)
    try:
        device = await device_store.get_device_by_id(device_id)
    except (DeviceStoreUnavailable, Exception):  # noqa: BLE001
        return None
    if not device or device.get("revoked_at") is not None:
        return None

    counts_task = asyncio.create_task(_safe_counts(memory_store))
    activity_task = asyncio.create_task(_safe_activity(memory_store))
    counts, activity = await asyncio.gather(counts_task, activity_task)
    return build_user_row(
        device,
        counts=counts.get(device_id),
        last_memory_at=activity.get(device_id),
        now=now,
        detail=True,
    )
