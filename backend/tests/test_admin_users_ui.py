"""Stage 6B-2: Users table and User Detail UI tests."""
from __future__ import annotations

from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.responses import RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.testclient import TestClient

import admin_api
from admin_auth import get_login_limiter
from config import settings
from devices import InMemoryDeviceStore
from memory_store import InMemoryMemoryStore

WEB_DIR = Path(__file__).resolve().parents[1] / "web"
CONTROL_DIR = WEB_DIR / "control"

LOCKED_COLUMNS = (
    "User / Device",
    "Last Active",
    "App Version",
    "Memory",
    "Personality",
    "Status",
    "Open",
)

DETAIL_LABELS = (
    "Device ID",
    "First connected",
    "Last active",
    "App version",
    "Language",
    "Machine model",
    "Memory connected",
    "Memory connection health",
    "Assigned personality",
    "Approved memories",
    "Proposed memories",
    "Recent Action Flows",
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
def client(admin_env, monkeypatch):
    devices = InMemoryDeviceStore()
    memory = InMemoryMemoryStore()
    monkeypatch.setattr(admin_api, "get_device_store", lambda: devices)
    monkeypatch.setattr(admin_api, "get_memory_store", lambda: memory)
    app = FastAPI()
    app.include_router(admin_api.router)

    @app.get("/")
    async def home():
        return RedirectResponse(url="/admin/login", status_code=303)

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    if WEB_DIR.exists():
        app.mount("/static", StaticFiles(directory=str(WEB_DIR)), name="static")
    return TestClient(app, follow_redirects=False)


def _login(client: TestClient):
    assert client.post("/admin/api/login", json={"password": "test-admin-password-ok"}).status_code == 200


def test_unauthenticated_users_redirects(client):
    r = client.get("/admin/users")
    assert r.status_code == 303
    assert r.headers["location"] == "/admin/login"


def test_authenticated_users_contains_ui(client):
    _login(client)
    r = client.get("/admin/users")
    assert r.status_code == 200
    assert 'id="usersRoot"' in r.text
    assert 'id="usersTable"' in r.text
    for col in LOCKED_COLUMNS:
        assert col in r.text
    assert 'id="usersSearch"' in r.text
    assert 'id="usersMemoryFilter"' in r.text
    assert 'id="usersStatusFilter"' in r.text
    assert 'id="usersRefreshBtn"' in r.text
    assert 'id="usersPrevBtn"' in r.text
    assert 'id="usersNextBtn"' in r.text
    assert "No users connected yet" in r.text
    assert "Loading users" in r.text
    assert "Unable to load users right now." in r.text
    assert 'id="usersRetryBtn"' in r.text


def test_user_detail_shell_route(client):
    _login(client)
    r = client.get("/admin/users/aaaaaaaa-bbbb-cccc-dddd-eeeeeeee1048")
    assert r.status_code == 200
    assert "User Detail" in r.text
    assert 'id="userDetailRoot"' in r.text
    assert 'id="userDetailGrid"' in r.text
    assert "Recent Action Flows" in r.text
    assert 'data-path="/admin/users" class="cc-nav-link is-active"' in r.text
    assert "Back to Users" in r.text
    assert "User not found." in r.text
    assert "No recent Action Flows." in r.text
    assert "Unable to load this user right now." in r.text
    js = (CONTROL_DIR / "shell.js").read_text(encoding="utf-8")
    for label in DETAIL_LABELS:
        if label == "Recent Action Flows":
            continue
        assert label in js


def test_users_js_api_calls_and_safety():
    js = (CONTROL_DIR / "shell.js").read_text(encoding="utf-8")
    assert 'fetch("/admin/api/users?"' in js
    assert 'fetch("/admin/api/users/"' in js
    assert "setInterval" not in js
    assert "localStorage" not in js
    assert "API_KEY" not in js
    assert "token_hash" not in js
    assert "service_role" not in js
    assert 'window.location.href = "/admin/login"' in js
    assert "User not found" in (CONTROL_DIR / "shell.html").read_text(encoding="utf-8")
    assert "No users connected yet" in (CONTROL_DIR / "shell.html").read_text(encoding="utf-8")
    for banned in ("Revoke", "Assign personality", "Disable memory", "Delete user", "Enable memory"):
        assert banned not in js


def test_no_write_buttons_in_users_markup():
    html = (CONTROL_DIR / "shell.html").read_text(encoding="utf-8")
    for banned in ("Revoke", "Assign personality", "Disable memory", "Delete device"):
        assert banned not in html
    assert "token_hash" not in html
    assert "metadata" not in html.lower() or "raw metadata" not in html.lower()


def test_responsive_users_css():
    css = (CONTROL_DIR / "control.css").read_text(encoding="utf-8")
    assert "cc-users-table" in css
    assert "cc-detail-grid" in css
    assert "@media (max-width: 900px)" in css
    assert "cc-users-table thead" in css
    assert "@media (max-width: 640px)" in css


def test_other_sections_remain_placeholders(client):
    _login(client)
    for path in ("/admin/content", "/admin/action-flows", "/admin/hermes", "/admin/settings"):
        assert "This section will be built in the next stage." in client.get(path).text


def test_overview_unchanged(client):
    _login(client)
    r = client.get("/admin")
    assert 'id="overviewRoot"' in r.text
    assert client.get("/admin/api/overview").status_code == 200


def test_users_api_still_works(client):
    _login(client)
    r = client.get("/admin/api/users")
    assert r.status_code == 200
    assert "users" in r.json()


def test_android_version_unchanged():
    gradle = (Path(__file__).resolve().parents[2] / "android" / "app" / "build.gradle.kts").read_text(
        encoding="utf-8"
    )
    assert 'versionName = "1.4.12"' in gradle
