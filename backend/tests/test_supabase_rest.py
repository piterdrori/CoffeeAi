"""Tests for shared Supabase PostgREST header construction and sanitized errors."""
from __future__ import annotations

import logging

import httpx
import pytest

from supabase_rest import (
    SupabaseKeyError,
    build_supabase_rest_headers,
    log_supabase_http_error,
    validate_privileged_server_key,
)


def _fake_jwt(role: str = "service_role") -> str:
    import base64
    import json

    header = base64.urlsafe_b64encode(json.dumps({"alg": "HS256", "typ": "JWT"}).encode()).decode().rstrip("=")
    payload = base64.urlsafe_b64encode(json.dumps({"role": role, "iss": "supabase"}).encode()).decode().rstrip("=")
    return f"{header}.{payload}.signature"


SECRET_KEY = "sb_secret_" + ("x" * 40)
PUBLISHABLE_KEY = "sb_publishable_" + ("y" * 40)
JWT_KEY = _fake_jwt()


def test_sb_secret_headers_include_apikey_exclude_bearer():
    headers = build_supabase_rest_headers(SECRET_KEY)
    assert headers["apikey"] == SECRET_KEY
    assert "Authorization" not in headers
    assert headers["Content-Type"] == "application/json"


def test_legacy_jwt_headers_include_apikey_and_bearer():
    headers = build_supabase_rest_headers(JWT_KEY)
    assert headers["apikey"] == JWT_KEY
    assert headers["Authorization"] == f"Bearer {JWT_KEY}"


def test_publishable_key_rejected_for_privileged_store():
    with pytest.raises(SupabaseKeyError):
        validate_privileged_server_key(PUBLISHABLE_KEY)
    with pytest.raises(SupabaseKeyError):
        build_supabase_rest_headers(PUBLISHABLE_KEY)


def test_unknown_key_format_rejected():
    with pytest.raises(SupabaseKeyError):
        validate_privileged_server_key("not-a-valid-key-format")


def test_key_value_never_in_exception_messages():
    with pytest.raises(SupabaseKeyError) as exc:
        validate_privileged_server_key(PUBLISHABLE_KEY)
    assert PUBLISHABLE_KEY not in str(exc.value)


def test_log_supabase_http_error_never_logs_secret(caplog):
    request = httpx.Request("POST", "https://example.supabase.co/rest/v1/devices")
    response = httpx.Response(
        401,
        request=request,
        json={"code": "PGRST301", "message": f"Invalid JWT containing {SECRET_KEY}"},
    )
    exc = httpx.HTTPStatusError("401", request=request, response=response)
    with caplog.at_level(logging.WARNING):
        log_supabase_http_error(store="device", operation="create_device", exc=exc)
    assert SECRET_KEY not in caplog.text
    assert "create_device" in caplog.text
    assert "401" in caplog.text


def test_device_and_memory_stores_share_header_helper():
    from devices import SupabaseDeviceStore
    from memory_store import SupabaseMemoryStore

    device_headers = SupabaseDeviceStore("https://example.supabase.co", SECRET_KEY)._headers
    memory_headers = SupabaseMemoryStore("https://example.supabase.co", SECRET_KEY)._headers
    assert device_headers == memory_headers
    assert device_headers == build_supabase_rest_headers(SECRET_KEY)
