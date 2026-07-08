"""Stage 1 device-identity API: registration, authentication dependency, and DB health.

Kept in its own module (importing only fastapi + ``devices``) so it is unit-testable without the
heavy ``chromadb`` memory stack that ``main`` pulls in.
"""
from __future__ import annotations

from typing import Any

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Request
from pydantic import BaseModel, Field

from devices import (
    DeviceStore,
    DeviceStoreUnavailable,
    RateLimiter,
    generate_device_token,
    get_device_store,
    get_register_limiter,
    hash_token,
    validate_install_id,
)

router = APIRouter()

# Paths that must NOT be gated by the legacy shared API key in main's middleware.
# - register is a rate-limited bootstrap path (gated separately);
# - device-scoped paths are authenticated by the require_device dependency (bearer token);
# - the DB health check is public.
REGISTER_PATH = "/v1/devices/register"
DEVICE_SCOPED_PREFIXES = ("/v1/devices/me",)
PUBLIC_DEVICE_PATHS = {"/v1/health/database"}


class RegisterRequest(BaseModel):
    install_id: str = Field(min_length=16, max_length=128)
    platform: str = "android"
    app_version: str | None = None


def store_dependency() -> DeviceStore:
    return get_device_store()


def limiter_dependency() -> RateLimiter:
    return get_register_limiter()


async def _safe_touch(store: DeviceStore, device_id: str, app_version: str | None) -> None:
    try:
        await store.touch_last_seen(device_id, app_version)
    except Exception:  # noqa: BLE001 - last_seen is best-effort, never fail the request
        pass


async def require_device(
    request: Request,
    background: BackgroundTasks,
    store: DeviceStore = Depends(store_dependency),
) -> str:
    """Reusable dependency: authenticate a device by its bearer token and return its device_id."""
    auth = request.headers.get("authorization", "")
    if not auth[:7].lower() == "bearer ":
        raise HTTPException(status_code=401, detail="missing_bearer_token")
    token = auth[7:].strip()
    if not token:
        raise HTTPException(status_code=401, detail="missing_bearer_token")
    token_hash = hash_token(token)
    try:
        device = await store.get_active_by_token_hash(token_hash)
    except DeviceStoreUnavailable:
        raise HTTPException(status_code=503, detail="device_store_unavailable")
    if device is None:
        raise HTTPException(status_code=401, detail="invalid_or_revoked_token")
    device_id = device["id"]
    request.state.device_id = device_id
    background.add_task(_safe_touch, store, device_id, None)
    return device_id


@router.post(REGISTER_PATH)
async def register_device(
    body: RegisterRequest,
    request: Request,
    store: DeviceStore = Depends(store_dependency),
    limiter: RateLimiter = Depends(limiter_dependency),
) -> dict[str, Any]:
    client_key = request.client.host if request.client else "unknown"
    if not limiter.allow(client_key):
        raise HTTPException(status_code=429, detail="registration_rate_limited")
    if not validate_install_id(body.install_id):
        raise HTTPException(status_code=400, detail="invalid_install_id")

    platform = (body.platform or "android").strip().lower()[:32] or "android"
    app_version = (body.app_version or None)
    # A fresh token is minted on every registration; only its salted hash is stored. Repeat
    # registration for a known install_id rotates the token in place (no duplicate device).
    token = generate_device_token()
    token_hash = hash_token(token)
    try:
        existing = await store.get_by_install_id(body.install_id)
        if existing is None:
            row = await store.create_device(body.install_id, token_hash, platform, app_version)
        else:
            row = await store.rotate_token(existing["id"], token_hash, app_version)
    except DeviceStoreUnavailable:
        raise HTTPException(status_code=503, detail="device_store_unavailable")

    # Only the newly issued token is returned; never the hash or any other secret.
    return {"device_id": row["id"], "device_token": token, "token_type": "Bearer"}


@router.get("/v1/devices/me")
async def device_me(device_id: str = Depends(require_device)) -> dict[str, Any]:
    return {"device_id": device_id}


@router.get("/v1/health/database")
async def health_database(store: DeviceStore = Depends(store_dependency)) -> dict[str, Any]:
    try:
        status = await store.health()
    except Exception:  # noqa: BLE001
        status = {"readable": False, "writable": False}
    readable = bool(status.get("readable"))
    writable = status.get("writable")
    ok = readable
    body: dict[str, Any] = {
        "status": "ok" if ok else "degraded",
        "database": "connected" if ok else "unavailable",
        "durable": store.durable,
        "readable": readable,
    }
    if isinstance(writable, bool):
        body["writable"] = writable
    else:
        body["writable"] = "unknown"
    return body
