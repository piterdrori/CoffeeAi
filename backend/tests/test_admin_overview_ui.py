"""Stage 6A-4: Overview page UI wired to GET /admin/api/overview."""
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

CARD_TITLES = (
    "Backend",
    "Database",
    "Hermes",
    "Connected Users",
    "Memory Proposals",
    "Active Action Flows",
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


def test_authenticated_admin_contains_overview_container(client):
    _login(client)
    r = client.get("/admin")
    assert r.status_code == 200
    assert 'id="overviewRoot"' in r.text
    assert 'id="overviewGrid"' in r.text
    assert 'id="recentActivitySection"' in r.text


def test_non_overview_routes_still_placeholders(client):
    _login(client)
    for path in (
        "/admin/users",
        "/admin/content",
        "/admin/action-flows",
        "/admin/hermes",
        "/admin/settings",
    ):
        r = client.get(path)
        assert r.status_code == 200
        assert "This section will be built in the next stage." in r.text
        assert 'id="overviewRoot"' in r.text  # shared shell includes markup
        # JS only activates on /admin; placeholder remains the visible section copy


def test_six_card_titles_and_recent_activity(client):
    _login(client)
    text = client.get("/admin").text
    for title in CARD_TITLES:
        assert title in text
    assert "Recent Activity" in text
    assert "No recent activity yet." in text


def test_refresh_and_retry_buttons_exist(client):
    _login(client)
    text = client.get("/admin").text
    assert 'id="overviewRefreshBtn"' in text
    assert "Refresh" in text
    assert 'id="overviewRetryBtn"' in text
    assert "Retry" in text
    assert "Unable to load the Overview right now." in text


def test_overview_js_fetches_api_no_polling_no_secrets():
    js = (CONTROL_DIR / "shell.js").read_text(encoding="utf-8")
    assert 'fetch("/admin/api/overview"' in js
    assert "setInterval" not in js
    assert "setTimeout" not in js
    assert "localStorage" not in js
    assert "API_KEY" not in js
    assert "service_role" not in js
    assert "SUPABASE" not in js
    assert 'window.location.href = "/admin/login"' in js
    assert "Unable to load the Overview right now." in (CONTROL_DIR / "shell.html").read_text(
        encoding="utf-8"
    )


def test_action_flows_not_configured_and_review_link_markup():
    html = (CONTROL_DIR / "shell.html").read_text(encoding="utf-8")
    assert 'id="metaFlows"' in html
    assert 'id="reviewProposalsLink"' in html
    assert 'href="/admin/content"' in html
    js = (CONTROL_DIR / "shell.js").read_text(encoding="utf-8")
    assert 'Not configured' in js
    assert "Active flows" in js
    assert "proposals > 0" in js or "proposals>0" in js.replace(" ", "")


def test_no_raw_json_container():
    html = (CONTROL_DIR / "shell.html").read_text(encoding="utf-8")
    assert "<pre" not in html.lower()
    assert "raw json" not in html.lower()
    assert "request_id" not in html
    js = (CONTROL_DIR / "shell.js").read_text(encoding="utf-8")
    assert "request_id" not in js
    assert "JSON.stringify" not in js


def test_responsive_css_for_overview_grid():
    css = (CONTROL_DIR / "control.css").read_text(encoding="utf-8")
    assert "cc-overview-grid" in css
    assert "grid-template-columns: repeat(3" in css
    assert "grid-template-columns: repeat(2" in css
    assert "@media (max-width: 640px)" in css
    assert "grid-template-columns: 1fr" in css


def test_overview_api_still_works_with_ui(client):
    _login(client)
    r = client.get("/admin/api/overview")
    assert r.status_code == 200
    body = r.json()
    assert body["active_action_flows"]["configured"] is False
    assert body["memory_proposals"]["count"] == 0
    assert isinstance(body["recent_activity"], list)


def test_login_and_health_unchanged(client):
    assert client.get("/admin/login").status_code == 200
    assert client.get("/health").json()["status"] == "ok"
    assert client.get("/").status_code == 303


def test_android_version_unchanged():
    gradle = (Path(__file__).resolve().parents[2] / "android" / "app" / "build.gradle.kts").read_text(
        encoding="utf-8"
    )
    assert 'versionName = "1.4.12"' in gradle
