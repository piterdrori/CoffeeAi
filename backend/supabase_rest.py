"""Shared Supabase PostgREST authentication and error helpers.

Server-side stores use the privileged Supabase server key (legacy ``service_role`` JWT or
``sb_secret_...``). New secret keys must be sent only on the ``apikey`` header — they are not JWTs
and must not appear in ``Authorization: Bearer``.
"""
from __future__ import annotations

import json
import logging
from typing import Any

import httpx

logger = logging.getLogger(__name__)


class SupabaseKeyError(ValueError):
    """Raised when SUPABASE_SERVICE_ROLE_KEY is missing or not a privileged server key."""


def validate_privileged_server_key(key: str) -> None:
    """Fail closed if the configured key cannot authorize server-side PostgREST writes."""
    if not key or not key.strip():
        raise SupabaseKeyError("supabase_server_key_missing")
    key = key.strip()
    if key.startswith("sb_publishable_"):
        raise SupabaseKeyError("supabase_publishable_key_not_allowed")
    if key.startswith("sb_secret_"):
        return
    if key.startswith("eyJ"):
        return
    raise SupabaseKeyError("supabase_server_key_format_unsupported")


def build_supabase_rest_headers(server_key: str, *, include_content_type: bool = True) -> dict[str, str]:
    """Build PostgREST headers for a privileged Supabase server key."""
    validate_privileged_server_key(server_key)
    headers: dict[str, str] = {"apikey": server_key}
    if server_key.startswith("eyJ"):
        headers["Authorization"] = f"Bearer {server_key}"
    if include_content_type:
        headers["Content-Type"] = "application/json"
    return headers


def _sanitize_postgrest_message(message: Any) -> str | None:
    if message is None:
        return None
    text = str(message)[:200]
    for needle in ("sb_secret_", "sb_publishable_", "eyJ"):
        if needle in text:
            return "redacted"
    return text


def log_supabase_http_error(*, store: str, operation: str, exc: httpx.HTTPError) -> None:
    """Record sanitized PostgREST failure metadata. Never logs headers or secret values."""
    http_status: int | None = None
    pg_code: str | None = None
    message: str | None = None
    if isinstance(exc, httpx.HTTPStatusError):
        http_status = exc.response.status_code
        try:
            body = exc.response.json()
            if isinstance(body, dict):
                pg_code = body.get("code")
                message = _sanitize_postgrest_message(body.get("message"))
        except (json.JSONDecodeError, ValueError):
            message = "non_json_error_body"
    logger.warning(
        "supabase_store_error store=%s operation=%s http_status=%s pg_code=%s message=%s",
        store,
        operation,
        http_status,
        pg_code,
        message,
    )


def device_store_http_error(operation: str, exc: httpx.HTTPError) -> None:
    log_supabase_http_error(store="device", operation=operation, exc=exc)


def memory_store_http_error(operation: str, exc: httpx.HTTPError) -> None:
    log_supabase_http_error(store="memory", operation=operation, exc=exc)
