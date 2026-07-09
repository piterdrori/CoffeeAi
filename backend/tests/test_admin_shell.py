"""Stage 6A-2: shared Control Center shell and navigation tests."""
from __future__ import annotations

from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from fastapi.testclient import TestClient

import admin_api
from admin_auth import get_login_limiter
from config import settings

WEB_DIR = Path(__file__).resolve().parents[1] / "web"
CONTROL_DIR = WEB_DIR / "control"

ROUTES = {
    "/admin": {
        "title": "Overview",
        "description": "See the current state of the CoffeeAI backend.",
    },
    "/admin/users": {
        "title": "Users",
        "description": "See who connected to CoffeeAI and whether memory is active.",
    },
    "/admin/content": {
        "title": "Content",
        "description": "Manage memory, help content, machine knowledge, videos, and personalities.",
    },
    "/admin/action-flows": {
        "title": "Action Flows",
        "description": "Program exact responses, choices, videos, and machine actions.",
    },
    "/admin/hermes": {
        "title": "Hermes",
        "description": "See Hermes status, test routing, and review prompt suggestions.",
    },
    "/admin/settings": {
        "title": "Settings",
        "description": "Control the main CoffeeAI backend options.",
    },
}

NAV_HREFS = (
    'href="/admin"',
    'href="/admin/users"',
    'href="/admin/content"',
    'href="/admin/action-flows"',
    'href="/admin/hermes"',
    'href="/admin/settings"',
)


@pytest.fixture
def admin_env(monkeypatch):
    monkeypatch.setattr(settings, "CONTROL_CENTER_PASSWORD", "test-admin-password-ok")
    monkeypatch.setattr(settings, "CONTROL_CENTER_USERNAME", "")
    monkeypatch.setattr(settings, "ADMIN_SESSION_SECRET", "test-session-secret-32chars-min!!")
    monkeypatch.setattr(settings, "ADMIN_SESSION_TTL_SECONDS", 8 * 3600)
    monkeypatch.setattr(settings, "ADMIN_LOGIN_MAX_FAILURES", 5)
    monkeypatch.setattr(settings, "ADMIN_LOGIN_WINDOW_SECONDS", 600)
    monkeypatch.setattr(settings, "ADMIN_COOKIE_SECURE", False)
    monkeypatch.setattr(settings, "ADMIN_COOKIE_SAMESITE", "lax")
    get_login_limiter().reset()
    yield
    get_login_limiter().reset()


@pytest.fixture
def client(admin_env):
    app = FastAPI()
    app.include_router(admin_api.router)

    @app.get("/")
    async def home():
        from fastapi.responses import RedirectResponse

        return RedirectResponse(url="/admin/login", status_code=303)

    @app.get("/setup")
    async def setup():
        from fastapi.responses import RedirectResponse

        return RedirectResponse(url="/admin/login", status_code=303)

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    if WEB_DIR.exists():
        app.mount("/static", StaticFiles(directory=str(WEB_DIR)), name="static")
    return TestClient(app, follow_redirects=False)


def _login(client: TestClient):
    return client.post("/admin/api/login", json={"password": "test-admin-password-ok"})


def _authed(client: TestClient):
    assert _login(client).status_code == 200


@pytest.mark.parametrize("path,meta", list(ROUTES.items()))
def test_authenticated_shell_per_route(client, path, meta):
    _authed(client)
    r = client.get(path)
    assert r.status_code == 200
    assert 'class="cc-sidebar"' in r.text or "cc-sidebar" in r.text
    assert 'class="cc-workspace"' in r.text or "cc-workspace" in r.text
    assert meta["title"] in r.text
    assert meta["description"] in r.text
    if path == "/admin":
        assert 'id="overviewRoot"' in r.text
        assert "Backend" in r.text
        assert "Recent Activity" in r.text
        assert 'id="overviewRefreshBtn"' in r.text
    else:
        assert "This section will be built in the next stage." in r.text
    for href in NAV_HREFS:
        assert href in r.text
    assert 'id="logoutBtn"' in r.text
    assert 'id="envBadge"' in r.text
    assert "Local" in r.text or "Production" in r.text
    assert 'id="menuBtn"' in r.text
    assert "API_KEY" not in r.text
    assert "localStorage" not in r.text


def test_active_nav_highlights_overview(client):
    _authed(client)
    r = client.get("/admin")
    assert 'data-path="/admin" class="cc-nav-link is-active"' in r.text
    assert 'data-path="/admin/users" class="cc-nav-link is-active"' not in r.text


def test_active_nav_highlights_users(client):
    _authed(client)
    r = client.get("/admin/users")
    assert 'data-path="/admin/users" class="cc-nav-link is-active"' in r.text
    assert 'data-path="/admin" class="cc-nav-link is-active"' not in r.text


def test_active_nav_highlights_settings(client):
    _authed(client)
    r = client.get("/admin/settings")
    assert 'data-path="/admin/settings" class="cc-nav-link is-active"' in r.text


def test_unauthenticated_still_redirects(client):
    for path in ROUTES:
        r = client.get(path)
        assert r.status_code == 303
        assert r.headers["location"] == "/admin/login"


def test_admin_api_still_protected(client):
    assert client.get("/admin/api/session").status_code == 401
    assert client.get("/admin/api/overview").status_code == 401


def test_shell_assets_served(client):
    assert client.get("/static/control/control.css").status_code == 200
    assert client.get("/static/control/shell.js").status_code == 200
    css = client.get("/static/control/control.css").text
    assert "cc-sidebar" in css
    assert "@media" in css


def test_public_routes_unchanged(client):
    root = client.get("/")
    assert root.status_code == 303
    assert root.headers["location"] == "/admin/login"
    setup = client.get("/setup")
    assert setup.status_code == 303
    assert setup.headers["location"] == "/admin/login"
    assert client.get("/health").json()["status"] == "ok"


def test_placeholder_html_retired():
    assert not (CONTROL_DIR / "placeholder.html").exists()
    assert (CONTROL_DIR / "shell.html").exists()


def test_android_version_unchanged():
    gradle = Path(__file__).resolve().parents[2] / "android" / "app" / "build.gradle.kts"
    text = gradle.read_text(encoding="utf-8")
    assert 'versionName = "1.4.12"' in text
