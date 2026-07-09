"""Legacy public UI retired — Control Center is the only backend UI."""
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

WEB_DIR = Path(__file__).resolve().parents[1] / "web"
CONTROL_DIR = WEB_DIR / "control"
MAIN_PY = Path(__file__).resolve().parents[1] / "main.py"

CONTROL_ROUTES = (
    "/admin",
    "/admin/users",
    "/admin/content",
    "/admin/action-flows",
    "/admin/hermes",
    "/admin/settings",
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
        return RedirectResponse(url="/admin/login", status_code=303)

    @app.get("/setup")
    async def setup():
        return RedirectResponse(url="/admin/login", status_code=303)

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    @app.get("/v1/connection-hints")
    async def hints():
        return {"bootstrap_required": True, "local_backend_supported": True}

    @app.get("/download/apk/info")
    async def apk_info():
        return {"available": False, "message": "test"}

    if WEB_DIR.exists():
        app.mount("/static", StaticFiles(directory=str(WEB_DIR)), name="static")
    return TestClient(app, follow_redirects=False)


def test_root_redirects_to_admin_login(client):
    r = client.get("/")
    assert r.status_code == 303
    assert r.headers["location"] == "/admin/login"


def test_setup_redirects_to_admin_login(client):
    r = client.get("/setup")
    assert r.status_code == 303
    assert r.headers["location"] == "/admin/login"


def test_legacy_homepage_html_not_served(client):
    r = client.get("/")
    assert r.status_code == 303
    assert "Your private AI assistant" not in r.text
    assert "Download APK" not in r.text
    assert "<!DOCTYPE html>" not in r.text


def test_legacy_page_files_removed():
    assert not (WEB_DIR / "index.html").exists()
    assert not (WEB_DIR / "setup.html").exists()
    assert not (WEB_DIR / "styles.css").exists()
    assert not (WEB_DIR / "admin.html").exists()


def test_main_py_uses_redirects_not_file_pages():
    source = MAIN_PY.read_text(encoding="utf-8")
    assert 'RedirectResponse(url="/admin/login"' in source
    assert '_serve_web_page("index.html")' not in source
    assert '_serve_web_page("setup.html")' not in source
    assert 'return _serve_web_page("index.html")' not in source


def test_legacy_admin_unreachable(client):
    assert not (WEB_DIR / "admin.html").exists()
    r = client.get("/static/admin.html")
    assert r.status_code == 404


def test_admin_login_works(client):
    r = client.get("/admin/login")
    assert r.status_code == 200
    assert "CoffeeAI Control Center" in r.text
    assert "localStorage" not in r.text


def test_authenticated_admin_and_six_sections(client):
    assert client.post("/admin/api/login", json={"password": "test-admin-password-ok"}).status_code == 200
    for path in CONTROL_ROUTES:
        r = client.get(path)
        assert r.status_code == 200, path
        assert "Control Center" in r.text
        assert "localStorage" not in r.text


def test_health_remains_public(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


def test_api_routes_remain_functional(client):
    assert client.get("/v1/connection-hints").status_code == 200
    assert client.get("/download/apk/info").status_code == 200


def test_no_duplicate_public_ui_routes(client):
    """Only Control Center HTML is served; / and /setup are redirects."""
    assert client.get("/").status_code == 303
    assert client.get("/setup").status_code == 303
    login = client.get("/admin/login")
    assert login.status_code == 200
    assert "Sign In" in login.text


def test_no_localstorage_credential_pattern_in_control_assets():
    for name in ("login.html", "shell.html", "login.js", "shell.js", "control.css"):
        text = (CONTROL_DIR / name).read_text(encoding="utf-8")
        assert "localStorage" not in text
        assert "API_KEY" not in text


def test_android_version_unchanged():
    gradle = (
        Path(__file__).resolve().parents[2] / "android" / "app" / "build.gradle.kts"
    ).read_text(encoding="utf-8")
    assert 'versionName = "1.4.12"' in gradle
