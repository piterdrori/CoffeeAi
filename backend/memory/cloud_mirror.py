"""Optional cloud mirror sync between local and cloud backends."""

from __future__ import annotations

import os
from typing import Any

import httpx

from config import settings


async def push_to_mirror(payload: dict[str, Any]) -> dict[str, Any]:
    mirror_url = os.getenv("CLOUD_MIRROR_URL", "").strip().rstrip("/")
    mirror_key = os.getenv("CLOUD_MIRROR_API_KEY", settings.API_KEY)
    if not mirror_url:
        return {"skipped": True, "reason": "CLOUD_MIRROR_URL not set"}

    async with httpx.AsyncClient(timeout=60.0) as client:
        response = await client.post(
            f"{mirror_url}/v1/sync/merge",
            json={"memories": payload.get("memories", []), "config": payload.get("config")},
            headers={"X-API-Key": mirror_key},
        )
        response.raise_for_status()
        return {"skipped": False, "mirror_response": response.json()}


async def pull_from_mirror() -> dict[str, Any]:
    mirror_url = os.getenv("CLOUD_MIRROR_URL", "").strip().rstrip("/")
    mirror_key = os.getenv("CLOUD_MIRROR_API_KEY", settings.API_KEY)
    if not mirror_url:
        return {"skipped": True, "reason": "CLOUD_MIRROR_URL not set"}

    async with httpx.AsyncClient(timeout=60.0) as client:
        response = await client.post(
            f"{mirror_url}/v1/sync/export",
            json={},
            headers={"X-API-Key": mirror_key},
        )
        response.raise_for_status()
        return response.json()
