"""Stage 6A-1: Control Center admin session auth (cookie-based, no DB).

Signed HttpOnly cookies. No passwords/API keys in browser storage.
Serverless rate limiting is per-instance only (approximate across replicas).
"""
from __future__ import annotations

import hashlib
import hmac
import os
import secrets
import time
from collections import defaultdict, deque
from typing import Any

from fastapi import Request, Response
from starlette.responses import JSONResponse, RedirectResponse

from config import settings

COOKIE_NAME = "coffeeai_admin_session"
LOGIN_PATH = "/admin/login"
GENERIC_AUTH_ERROR = "invalid_credentials"


def is_production_runtime() -> bool:
    return os.getenv("VERCEL") == "1" or (os.getenv("COFFEEAI_ENV") or "").lower() == "production"


def admin_auth_configured() -> bool:
    return bool(
        settings.CONTROL_CENTER_PASSWORD.strip()
        and settings.ADMIN_SESSION_SECRET.strip()
    )


def cookie_secure() -> bool:
    if settings.ADMIN_COOKIE_SECURE is not None:
        return bool(settings.ADMIN_COOKIE_SECURE)
    return is_production_runtime()


def _password_ok(provided: str) -> bool:
    expected = settings.CONTROL_CENTER_PASSWORD
    if not expected:
        return False
    return hmac.compare_digest(
        hashlib.sha256(provided.encode("utf-8")).digest(),
        hashlib.sha256(expected.encode("utf-8")).digest(),
    )


def _username_ok(provided: str | None) -> bool:
    expected = settings.CONTROL_CENTER_USERNAME.strip()
    if not expected:
        return True  # password-only mode
    return hmac.compare_digest((provided or "").strip(), expected)


def create_session_token(*, username: str = "admin") -> str:
    secret = settings.ADMIN_SESSION_SECRET.strip()
    if not secret:
        raise RuntimeError("admin_session_secret_missing")
    exp = int(time.time()) + int(settings.ADMIN_SESSION_TTL_SECONDS)
    body = f"{exp}:{username}"
    sig = hmac.new(secret.encode("utf-8"), body.encode("utf-8"), hashlib.sha256).hexdigest()
    return f"{body}:{sig}"


def verify_session_token(token: str | None) -> dict[str, Any] | None:
    if not token or not settings.ADMIN_SESSION_SECRET.strip():
        return None
    parts = token.split(":")
    if len(parts) != 3:
        return None
    exp_s, username, sig = parts
    try:
        exp = int(exp_s)
    except ValueError:
        return None
    body = f"{exp}:{username}"
    expected = hmac.new(
        settings.ADMIN_SESSION_SECRET.encode("utf-8"),
        body.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    if not hmac.compare_digest(sig, expected):
        return None
    if exp < int(time.time()):
        return None
    return {"username": username, "exp": exp}


def set_session_cookie(response: Response, token: str) -> None:
    response.set_cookie(
        key=COOKIE_NAME,
        value=token,
        max_age=int(settings.ADMIN_SESSION_TTL_SECONDS),
        path="/admin",
        httponly=True,
        secure=cookie_secure(),
        samesite=settings.ADMIN_COOKIE_SAMESITE,
    )


def clear_session_cookie(response: Response) -> None:
    response.delete_cookie(
        key=COOKIE_NAME,
        path="/admin",
        httponly=True,
        secure=cookie_secure(),
        samesite=settings.ADMIN_COOKIE_SAMESITE,
    )


def read_session(request: Request) -> dict[str, Any] | None:
    return verify_session_token(request.cookies.get(COOKIE_NAME))


def client_ip(request: Request) -> str:
    forwarded = (request.headers.get("x-forwarded-for") or "").split(",")[0].strip()
    if forwarded:
        return forwarded[:64]
    if request.client and request.client.host:
        return request.client.host[:64]
    return "unknown"


class LoginRateLimiter:
    """Failed-login limiter. Per-instance only — approximate on multi-instance serverless."""

    def __init__(self, max_failures: int, window_seconds: int) -> None:
        self._max = max_failures
        self._window = window_seconds
        self._hits: dict[str, deque[float]] = defaultdict(deque)

    def is_blocked(self, key: str, *, now: float | None = None) -> bool:
        now = now if now is not None else time.monotonic()
        bucket = self._hits[key]
        cutoff = now - self._window
        while bucket and bucket[0] < cutoff:
            bucket.popleft()
        return len(bucket) >= self._max

    def record_failure(self, key: str, *, now: float | None = None) -> None:
        now = now if now is not None else time.monotonic()
        bucket = self._hits[key]
        cutoff = now - self._window
        while bucket and bucket[0] < cutoff:
            bucket.popleft()
        bucket.append(now)

    def clear(self, key: str) -> None:
        self._hits.pop(key, None)

    def reset(self) -> None:
        self._hits.clear()


_login_limiter: LoginRateLimiter | None = None


def get_login_limiter() -> LoginRateLimiter:
    global _login_limiter
    if _login_limiter is None:
        _login_limiter = LoginRateLimiter(
            settings.ADMIN_LOGIN_MAX_FAILURES,
            settings.ADMIN_LOGIN_WINDOW_SECONDS,
        )
    return _login_limiter


def authenticate_credentials(password: str, username: str | None = None) -> bool:
    if not admin_auth_configured():
        return False
    return _password_ok(password) and _username_ok(username)


def unauthenticated_html_redirect() -> RedirectResponse:
    return RedirectResponse(url=LOGIN_PATH, status_code=303)


def unauthenticated_api() -> JSONResponse:
    return JSONResponse(status_code=401, content={"detail": "admin_auth_required"})


def require_admin_session(request: Request) -> dict[str, Any] | None:
    """Return session dict or None if missing/invalid/expired."""
    return read_session(request)


def new_csrf_nonce() -> str:
    return secrets.token_urlsafe(16)
