"""Stage 6A: Control Center login + shared authenticated shell.

6A-1: session auth. 6A-2: shared layout/navigation placeholders.
6A-3: Overview read API (no visible cards; no other section functionality).
"""
from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, HTTPException, Request, Response
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse
from pydantic import BaseModel, Field

from admin_auth import (
    GENERIC_AUTH_ERROR,
    admin_auth_configured,
    authenticate_credentials,
    clear_session_cookie,
    client_ip,
    create_session_token,
    get_login_limiter,
    is_production_runtime,
    require_admin_session,
    set_session_cookie,
    unauthenticated_api,
    unauthenticated_html_redirect,
)
from admin_overview import build_overview_payload
from config import settings
from devices import get_device_store
from memory_store import get_memory_store

router = APIRouter()

CONTROL_DIR = Path(__file__).resolve().parent / "web" / "control"

# Exact copy for Stage 6A-2 placeholders (no live data).
ROUTE_META: dict[str, dict[str, str]] = {
    "/admin": {
        "title": "Overview",
        "description": "See the current state of the CoffeeAI backend.",
        "nav_key": "overview",
    },
    "/admin/users": {
        "title": "Users",
        "description": "See who connected to CoffeeAI and whether memory is active.",
        "nav_key": "users",
    },
    "/admin/content": {
        "title": "Content",
        "description": "Manage memory, help content, machine knowledge, videos, and personalities.",
        "nav_key": "content",
    },
    "/admin/action-flows": {
        "title": "Action Flows",
        "description": "Program exact responses, choices, videos, and machine actions.",
        "nav_key": "flows",
    },
    "/admin/hermes": {
        "title": "Hermes",
        "description": "See Hermes status, test routing, and review prompt suggestions.",
        "nav_key": "hermes",
    },
    "/admin/settings": {
        "title": "Settings",
        "description": "Control the main CoffeeAI backend options.",
        "nav_key": "settings",
    },
}

_NAV_ACTIVE_TOKENS = {
    "overview": "{{ACTIVE_OVERVIEW}}",
    "users": "{{ACTIVE_USERS}}",
    "content": "{{ACTIVE_CONTENT}}",
    "flows": "{{ACTIVE_FLOWS}}",
    "hermes": "{{ACTIVE_HERMES}}",
    "settings": "{{ACTIVE_SETTINGS}}",
}


class LoginRequest(BaseModel):
    password: str = Field(min_length=1, max_length=256)
    username: str | None = Field(default=None, max_length=128)


def _serve_control(filename: str) -> FileResponse:
    path = CONTROL_DIR / filename
    if not path.exists():
        raise HTTPException(status_code=404, detail="page_not_found")
    return FileResponse(path)


def _normalize_admin_path(path: str) -> str:
    if path != "/" and path.endswith("/"):
        path = path.rstrip("/")
    if path == "/admin/" or path == "/admin":
        return "/admin"
    return path


def _env_badge() -> str:
    return "Production" if is_production_runtime() else "Local"


def render_shell(*, path: str, username: str) -> HTMLResponse:
    """Render the shared Control Center shell with route-specific copy."""
    path = _normalize_admin_path(path)
    meta = ROUTE_META.get(path) or ROUTE_META["/admin"]
    template = (CONTROL_DIR / "shell.html").read_text(encoding="utf-8")
    active_class = " is-active"
    replacements = {
        "{{PAGE_TITLE}}": meta["title"],
        "{{PAGE_DESCRIPTION}}": meta["description"],
        "{{ACTIVE_PATH}}": path,
        "{{USERNAME}}": username or "admin",
        "{{ENV_BADGE}}": _env_badge(),
    }
    for key, token in _NAV_ACTIVE_TOKENS.items():
        replacements[token] = active_class if meta["nav_key"] == key else ""
    html = template
    for old, new in replacements.items():
        html = html.replace(old, new)
    return HTMLResponse(content=html, status_code=200)


@router.get("/admin/login")
async def admin_login_page(request: Request) -> Response:
    session = require_admin_session(request)
    if session:
        return RedirectResponse(url="/admin", status_code=303)
    return _serve_control("login.html")


@router.post("/admin/api/login")
async def admin_login(body: LoginRequest, request: Request) -> Response:
    if not admin_auth_configured():
        return JSONResponse(status_code=503, content={"detail": "admin_not_configured"})

    limiter = get_login_limiter()
    ip = client_ip(request)
    if limiter.is_blocked(ip):
        return JSONResponse(status_code=429, content={"detail": "too_many_attempts"})

    if not authenticate_credentials(body.password, body.username):
        limiter.record_failure(ip)
        return JSONResponse(status_code=401, content={"detail": GENERIC_AUTH_ERROR})

    limiter.clear(ip)
    username = (body.username or "").strip() or (
        settings.CONTROL_CENTER_USERNAME.strip() or "admin"
    )
    token = create_session_token(username=username)
    resp = JSONResponse(
        content={"authenticated": True, "username": username},
        status_code=200,
    )
    set_session_cookie(resp, token)
    return resp


@router.post("/admin/api/logout")
async def admin_logout() -> Response:
    resp = JSONResponse(content={"authenticated": False}, status_code=200)
    clear_session_cookie(resp)
    return resp


@router.get("/admin/api/session")
async def admin_session(request: Request) -> Response:
    session = require_admin_session(request)
    if not session:
        return unauthenticated_api()
    return JSONResponse(
        content={
            "authenticated": True,
            "username": session.get("username"),
            "exp": session.get("exp"),
            "environment": _env_badge(),
        }
    )


@router.get("/admin/api/overview")
async def admin_overview(request: Request) -> Response:
    """Privacy-safe Overview aggregates. Session cookie only — no API key / bearer."""
    session = require_admin_session(request)
    if not session:
        return unauthenticated_api()
    payload = await build_overview_payload(
        device_store=get_device_store(),
        memory_store=get_memory_store(),
    )
    return JSONResponse(content=payload, status_code=200)


def _protected_shell(request: Request) -> Response:
    session = require_admin_session(request)
    if not session:
        return unauthenticated_html_redirect()
    return render_shell(
        path=request.url.path,
        username=str(session.get("username") or "admin"),
    )


@router.get("/admin")
@router.get("/admin/")
async def admin_home(request: Request) -> Response:
    return _protected_shell(request)


@router.get("/admin/users")
@router.get("/admin/content")
@router.get("/admin/action-flows")
@router.get("/admin/hermes")
@router.get("/admin/settings")
async def admin_section_shell(request: Request) -> Response:
    return _protected_shell(request)


@router.api_route("/admin/api/{rest:path}", methods=["GET", "POST", "PUT", "PATCH", "DELETE"])
async def admin_api_guard(rest: str, request: Request) -> Response:
    """Any future /admin/api/* route requires a session (except login, defined above)."""
    del rest
    session = require_admin_session(request)
    if not session:
        return unauthenticated_api()
    return JSONResponse(status_code=404, content={"detail": "not_found"})
