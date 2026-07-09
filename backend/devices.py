"""Stage 1: durable device identity.

Provides a small, dependency-light foundation the later stages (Favorites sync, summaries, durable
memory, Hermes) build on:

- one stable device per Android installation (``install_id``);
- one server-issued opaque bearer token per device (only its salted hash is stored);
- a storage abstraction with a durable Supabase/PostgREST backend and a non-durable in-memory
  backend so the API still boots for local dev / unit tests without credentials.

This module imports only the standard library, ``httpx`` (already a backend dependency), and
``config`` — never ``chromadb`` — so it can be unit-tested in isolation.
"""
from __future__ import annotations

import hashlib
import hmac
import re
import secrets
import time
import uuid
from abc import ABC, abstractmethod
from collections import defaultdict, deque
from datetime import datetime, timezone
from typing import Any

import httpx

from config import settings
from supabase_rest import (
    SupabaseKeyError,
    build_supabase_rest_headers,
    device_store_http_error,
)

# install_id is a client-generated opaque id (UUID/token). Constrain length + charset defensively.
_INSTALL_ID_RE = re.compile(r"^[A-Za-z0-9_-]{16,128}$")


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def validate_install_id(install_id: str | None) -> bool:
    return isinstance(install_id, str) and bool(_INSTALL_ID_RE.match(install_id))


def generate_device_token() -> str:
    """A strong, URL-safe opaque token. Returned to the client exactly once; never stored raw."""
    return secrets.token_urlsafe(32)


def hash_token(token: str) -> str:
    """Salted (peppered) SHA-256 hash of a device token. Only this hash is ever persisted."""
    pepper = settings.DEVICE_TOKEN_SIGNING_SECRET.encode("utf-8")
    return hashlib.sha256(pepper + b":" + token.encode("utf-8")).hexdigest()


def tokens_match(token: str, stored_hash: str) -> bool:
    """Constant-time comparison of a presented token against a stored hash."""
    return hmac.compare_digest(hash_token(token), stored_hash)


class DeviceStoreUnavailable(RuntimeError):
    """Raised when the durable store cannot be reached, so routes can fail safe (503)."""


class DeviceStore(ABC):
    durable: bool = False

    @abstractmethod
    async def health(self) -> dict[str, bool | str]: ...

    @abstractmethod
    async def get_by_install_id(self, install_id: str) -> dict[str, Any] | None: ...

    @abstractmethod
    async def create_device(self, install_id: str, token_hash: str, platform: str, app_version: str | None) -> dict[str, Any]: ...

    @abstractmethod
    async def rotate_token(self, device_id: str, token_hash: str, app_version: str | None) -> dict[str, Any]: ...

    @abstractmethod
    async def get_active_by_token_hash(self, token_hash: str) -> dict[str, Any] | None: ...

    @abstractmethod
    async def touch_last_seen(self, device_id: str, app_version: str | None = None) -> None: ...

    @abstractmethod
    async def count_connected_devices(self) -> int:
        """Count registered devices that are not revoked. Returns an integer only."""
        ...


class InMemoryDeviceStore(DeviceStore):
    """Non-durable store for local dev / tests. Clearly not for production data."""

    durable = False

    def __init__(self) -> None:
        self._by_id: dict[str, dict[str, Any]] = {}

    async def health(self) -> dict[str, bool | str]:
        return {"readable": True, "writable": True}

    async def get_by_install_id(self, install_id: str) -> dict[str, Any] | None:
        for row in self._by_id.values():
            if row["install_id"] == install_id:
                return dict(row)
        return None

    async def create_device(self, install_id: str, token_hash: str, platform: str, app_version: str | None) -> dict[str, Any]:
        now = utc_now_iso()
        row = {
            "id": str(uuid.uuid4()),
            "install_id": install_id,
            "token_hash": token_hash,
            "platform": platform,
            "app_version": app_version,
            "created_at": now,
            "last_seen_at": now,
            "revoked_at": None,
            "metadata": {},
        }
        self._by_id[row["id"]] = row
        return dict(row)

    async def rotate_token(self, device_id: str, token_hash: str, app_version: str | None) -> dict[str, Any]:
        row = self._by_id[device_id]
        row["token_hash"] = token_hash
        row["revoked_at"] = None
        row["last_seen_at"] = utc_now_iso()
        if app_version is not None:
            row["app_version"] = app_version
        return dict(row)

    async def get_active_by_token_hash(self, token_hash: str) -> dict[str, Any] | None:
        for row in self._by_id.values():
            if row["revoked_at"] is None and hmac.compare_digest(row["token_hash"], token_hash):
                return dict(row)
        return None

    async def touch_last_seen(self, device_id: str, app_version: str | None = None) -> None:
        row = self._by_id.get(device_id)
        if row is not None:
            row["last_seen_at"] = utc_now_iso()
            if app_version:
                row["app_version"] = app_version

    async def count_connected_devices(self) -> int:
        return sum(1 for row in self._by_id.values() if row.get("revoked_at") is None)

    # Test-only helper (not part of the interface): expose rows for hashing assertions.
    def _debug_rows(self) -> list[dict[str, Any]]:
        return [dict(r) for r in self._by_id.values()]


class SupabaseDeviceStore(DeviceStore):
    """Durable store backed by Supabase PostgREST using the server-only service-role key.

    Authorization for device data in Stage 1 is enforced in FastAPI (service-role bypasses RLS);
    RLS is added in a later stage when anon/device-scoped access is introduced.
    """

    durable = True

    def __init__(self, url: str, service_role_key: str, timeout: float = 5.0) -> None:
        self._base = f"{url.rstrip('/')}/rest/v1"
        self._headers = build_supabase_rest_headers(service_role_key)
        self._timeout = timeout

    async def _client(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(timeout=self._timeout, headers=self._headers)

    async def health(self) -> dict[str, bool | str]:
        readable = False
        writable: bool | str = False
        try:
            async with await self._client() as client:
                resp = await client.get(f"{self._base}/devices", params={"select": "id", "limit": 1})
                readable = resp.status_code == 200
        except httpx.HTTPError:
            return {"readable": False, "writable": False}

        if not readable:
            return {"readable": False, "writable": False}

        probe_install = f"healthprobe{uuid.uuid4().hex[:16]}"
        payload = {
            "install_id": probe_install,
            "token_hash": "0" * 64,
            "platform": "health",
            "app_version": None,
        }
        try:
            async with await self._client() as client:
                resp = await client.post(
                    f"{self._base}/devices",
                    params={"select": "id"},
                    headers={**self._headers, "Prefer": "return=representation"},
                    json=payload,
                )
                resp.raise_for_status()
                rows = resp.json()
                row_id = rows[0]["id"] if isinstance(rows, list) and rows else None
                writable = True
                if row_id:
                    await client.delete(
                        f"{self._base}/devices",
                        params={"id": f"eq.{row_id}"},
                    )
        except httpx.HTTPError as exc:
            device_store_http_error("health_write_probe", exc)
            writable = False

        return {"readable": readable, "writable": writable}

    async def get_by_install_id(self, install_id: str) -> dict[str, Any] | None:
        try:
            async with await self._client() as client:
                resp = await client.get(
                    f"{self._base}/devices",
                    params={"install_id": f"eq.{install_id}", "select": "*", "limit": 1},
                )
                resp.raise_for_status()
                rows = resp.json()
        except httpx.HTTPError as exc:
            device_store_http_error("get_by_install_id", exc)
            raise DeviceStoreUnavailable(str(exc)) from exc
        return rows[0] if rows else None

    async def create_device(self, install_id: str, token_hash: str, platform: str, app_version: str | None) -> dict[str, Any]:
        payload = {
            "install_id": install_id,
            "token_hash": token_hash,
            "platform": platform,
            "app_version": app_version,
        }
        try:
            async with await self._client() as client:
                resp = await client.post(
                    f"{self._base}/devices",
                    params={"select": "*"},
                    headers={**self._headers, "Prefer": "return=representation"},
                    json=payload,
                )
                resp.raise_for_status()
                rows = resp.json()
        except httpx.HTTPError as exc:
            device_store_http_error("create_device", exc)
            raise DeviceStoreUnavailable(str(exc)) from exc
        return rows[0] if isinstance(rows, list) else rows

    async def rotate_token(self, device_id: str, token_hash: str, app_version: str | None) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "token_hash": token_hash,
            "revoked_at": None,
            "last_seen_at": utc_now_iso(),
        }
        if app_version is not None:
            payload["app_version"] = app_version
        try:
            async with await self._client() as client:
                resp = await client.patch(
                    f"{self._base}/devices",
                    params={"id": f"eq.{device_id}", "select": "*"},
                    headers={**self._headers, "Prefer": "return=representation"},
                    json=payload,
                )
                resp.raise_for_status()
                rows = resp.json()
        except httpx.HTTPError as exc:
            device_store_http_error("rotate_token", exc)
            raise DeviceStoreUnavailable(str(exc)) from exc
        return rows[0] if isinstance(rows, list) and rows else payload | {"id": device_id}

    async def get_active_by_token_hash(self, token_hash: str) -> dict[str, Any] | None:
        try:
            async with await self._client() as client:
                resp = await client.get(
                    f"{self._base}/devices",
                    params={
                        "token_hash": f"eq.{token_hash}",
                        "revoked_at": "is.null",
                        "select": "*",
                        "limit": 1,
                    },
                )
                resp.raise_for_status()
                rows = resp.json()
        except httpx.HTTPError as exc:
            device_store_http_error("get_active_by_token_hash", exc)
            raise DeviceStoreUnavailable(str(exc)) from exc
        return rows[0] if rows else None

    async def touch_last_seen(self, device_id: str, app_version: str | None = None) -> None:
        payload: dict[str, Any] = {"last_seen_at": utc_now_iso()}
        if app_version:
            payload["app_version"] = app_version
        try:
            async with await self._client() as client:
                await client.patch(
                    f"{self._base}/devices",
                    params={"id": f"eq.{device_id}"},
                    json=payload,
                )
        except httpx.HTTPError:
            # Best-effort — never block a normal request on a last_seen update.
            pass

    async def count_connected_devices(self) -> int:
        try:
            async with await self._client() as client:
                resp = await client.get(
                    f"{self._base}/devices",
                    params={"revoked_at": "is.null", "select": "id"},
                    headers={**self._headers, "Prefer": "count=exact", "Range": "0-0"},
                )
                if resp.status_code not in (200, 206):
                    raise DeviceStoreUnavailable("count_failed")
                content_range = resp.headers.get("content-range") or resp.headers.get("Content-Range") or ""
                # content-range: 0-0/123 or */0
                if "/" in content_range:
                    total = content_range.rsplit("/", 1)[-1]
                    if total.isdigit():
                        return int(total)
                rows = resp.json()
                return len(rows) if isinstance(rows, list) else 0
        except httpx.HTTPError as exc:
            device_store_http_error("count_connected_devices", exc)
            raise DeviceStoreUnavailable(str(exc)) from exc


class RateLimiter:
    """Best-effort in-memory sliding-window limiter (per serverless instance)."""

    def __init__(self, max_per_window: int, window_seconds: int) -> None:
        self._max = max_per_window
        self._window = window_seconds
        self._hits: dict[str, deque[float]] = defaultdict(deque)

    def allow(self, key: str, *, now: float | None = None) -> bool:
        now = now if now is not None else time.monotonic()
        bucket = self._hits[key]
        cutoff = now - self._window
        while bucket and bucket[0] < cutoff:
            bucket.popleft()
        if len(bucket) >= self._max:
            return False
        bucket.append(now)
        return True

    def reset(self) -> None:
        self._hits.clear()


_store: DeviceStore | None = None
_register_limiter: RateLimiter | None = None


def get_device_store() -> DeviceStore:
    global _store
    if _store is None:
        if settings.supabase_enabled:
            try:
                _store = SupabaseDeviceStore(settings.SUPABASE_URL, settings.SUPABASE_SERVICE_ROLE_KEY)
            except SupabaseKeyError as exc:
                raise RuntimeError("invalid_supabase_server_key_configuration") from exc
        else:
            _store = InMemoryDeviceStore()
    return _store


def get_register_limiter() -> RateLimiter:
    global _register_limiter
    if _register_limiter is None:
        _register_limiter = RateLimiter(
            settings.DEVICE_REGISTER_MAX_PER_WINDOW,
            settings.DEVICE_REGISTER_WINDOW_SECONDS,
        )
    return _register_limiter
