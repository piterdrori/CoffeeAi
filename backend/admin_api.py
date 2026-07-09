"""Stage 6A/6B: Control Center login, shell, Overview, and Users.

6A: session auth, shell, Overview.
6B-1: Users list/detail read APIs.
6B-2: Visible Users table and User Detail view (read-only).
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
from admin_users import (
    DEFAULT_PAGE_SIZE,
    MAX_PAGE_SIZE,
    get_admin_user_detail,
    list_admin_users,
)
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
    if path.startswith("/admin/users/"):
        return "/admin/users"
    return path


def _env_badge() -> str:
    return "Production" if is_production_runtime() else "Local"


def _route_meta_for(path: str) -> dict[str, str]:
    raw = path
    if raw != "/" and raw.endswith("/"):
        raw = raw.rstrip("/")
    if raw.startswith("/admin/users/") and raw != "/admin/users":
        return {
            "title": "User Detail",
            "description": "Review one CoffeeAI device and its memory connection.",
            "nav_key": "users",
        }
    normalized = _normalize_admin_path(raw)
    return ROUTE_META.get(normalized) or ROUTE_META["/admin"]


def render_shell(*, path: str, username: str) -> HTMLResponse:
    """Render the shared Control Center shell with route-specific copy."""
    meta = _route_meta_for(path)
    nav_path = _normalize_admin_path(path)
    template = (CONTROL_DIR / "shell.html").read_text(encoding="utf-8")
    active_class = " is-active"
    replacements = {
        "{{PAGE_TITLE}}": meta["title"],
        "{{PAGE_DESCRIPTION}}": meta["description"],
        "{{ACTIVE_PATH}}": nav_path,
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


@router.get("/admin/api/users")
async def admin_users_list(request: Request) -> Response:
    """Privacy-safe Users list. Session cookie only. Read-only."""
    session = require_admin_session(request)
    if not session:
        return unauthenticated_api()
    qp = request.query_params
    try:
        page = int(qp.get("page") or 1)
    except ValueError:
        page = 1
    try:
        page_size = int(qp.get("page_size") or DEFAULT_PAGE_SIZE)
    except ValueError:
        page_size = DEFAULT_PAGE_SIZE
    page_size = max(1, min(page_size, MAX_PAGE_SIZE))
    search = (qp.get("search") or "").strip() or None
    memory = (qp.get("memory") or "").strip() or None
    status = (qp.get("status") or "").strip() or None
    type_filter = (qp.get("type") or "").strip().lower() or None
    if memory not in (None, "connected", "not_connected"):
        memory = None
    if status not in (None, "active", "memory_active", "registered_only", "offline", "test", "unknown"):
        status = None
    if type_filter not in (None, "real", "test", "unknown", "all"):
        type_filter = None
    payload = await list_admin_users(
        device_store=get_device_store(),
        memory_store=get_memory_store(),
        page=page,
        page_size=page_size,
        search=search,
        memory=memory,
        status=status,
        type_filter=type_filter,
    )
    return JSONResponse(content=payload, status_code=200)


@router.get("/admin/api/users/{device_id}")
async def admin_user_detail(device_id: str, request: Request) -> Response:
    """Privacy-safe User detail. Session cookie only. Read-only."""
    session = require_admin_session(request)
    if not session:
        return unauthenticated_api()
    device_id = (device_id or "").strip()
    if not device_id or len(device_id) > 64:
        return JSONResponse(status_code=404, content={"detail": "not_found"})
    payload = await get_admin_user_detail(
        device_store=get_device_store(),
        memory_store=get_memory_store(),
        device_id=device_id,
    )
    if payload is None:
        return JSONResponse(status_code=404, content={"detail": "not_found"})
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


@router.get("/admin/users/{device_id}")
async def admin_user_detail_shell(device_id: str, request: Request) -> Response:
    """Shared shell for User Detail (read-only UI). device_id is used by client JS only."""
    del device_id
    return _protected_shell(request)


@router.api_route("/admin/api/{rest:path}", methods=["GET", "POST", "PUT", "PATCH", "DELETE"])
async def admin_api_guard(rest: str, request: Request) -> Response:
    """Any future /admin/api/* route requires a session (except login, defined above)."""
    del rest
    session = require_admin_session(request)
    if not session:
        return unauthenticated_api()
    return JSONResponse(status_code=404, content={"detail": "not_found"})
