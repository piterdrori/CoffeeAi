"""Security tests for public connection hints and admin HTML."""
from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from config import settings


def _connection_hints_payload() -> dict[str, Any]:
    """Mirror backend/main.py connection_hints without importing the full app stack."""
    port = settings.PORT
    vercel_host = os.getenv("VERCEL_URL", "").strip()
    cloud_url = f"https://{vercel_host}" if vercel_host else None
    return {
        "cloud_url": cloud_url,
        "bootstrap_required": True,
        "local_backend_supported": True,
        "phone_url": None,
        "emulator_url": f"http://10.0.2.2:{port}",
        "local_url": f"http://localhost:{port}",
        "lan_ip": None,
    }


@pytest.fixture
def client():
    app = FastAPI()

    @app.get("/v1/connection-hints")
    async def connection_hints() -> dict[str, Any]:
        return _connection_hints_payload()

    return TestClient(app)


def test_connection_hints_has_no_api_key_hint(client):
    resp = client.get("/v1/connection-hints")
    assert resp.status_code == 200
    body = resp.json()
    assert "api_key_hint" not in body
    assert body.get("bootstrap_required") is True


def test_connection_hints_never_returns_api_key_value(client):
    resp = client.get("/v1/connection-hints")
    assert settings.API_KEY not in resp.text


def test_main_connection_hints_source_has_no_api_key_hint():
    source = (Path(__file__).resolve().parents[1] / "main.py").read_text(encoding="utf-8")
    assert '"api_key_hint"' not in source


def test_admin_html_contains_no_active_api_key():
    html = (Path(__file__).resolve().parents[1] / "web" / "admin.html").read_text(encoding="utf-8")
    assert "api_key_hint" not in html
    assert "Default (dev):" not in html
    assert settings.API_KEY not in html


def test_setup_html_does_not_claim_key_is_shown_on_home_page():
    html = (Path(__file__).resolve().parents[1] / "web" / "setup.html").read_text(encoding="utf-8")
    assert "shown on the" not in html.lower()
