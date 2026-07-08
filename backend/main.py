from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import aiofiles
from fastapi import Depends, FastAPI, File, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from config import settings
from devices_api import DEVICE_SCOPED_PREFIXES, PUBLIC_DEVICE_PATHS, router as devices_router
from memory_api import MEMORY_DEVICE_SCOPED_PREFIXES, router as memory_router
from memory.provider import get_memory_provider

WEB_DIR = Path(__file__).resolve().parent / "web"

PUBLIC_PATHS = {
    "/",
    "/health",
    "/setup",
    "/admin",
    "/admin/",
    "/download/apk",
    "/download/apk/info",
    "/v1/app/version",
    "/v1/connection-hints",
    *PUBLIC_DEVICE_PATHS,
}


class PrefetchRequest(BaseModel):
    query: str = ""
    top_k: int | None = Field(default=None, ge=1, le=50)


class SyncTurnRequest(BaseModel):
    user: str = Field(min_length=1)
    assistant: str = Field(min_length=1)
    session_id: str = Field(min_length=1)


class ConfigUpdateRequest(BaseModel):
    system_prompt: str | None = None
    tone: str | None = None
    rules: list[str] | None = None
    model: str | None = None
    model_provider: str | None = None


class SyncMergeRequest(BaseModel):
    memories: list[dict[str, Any]] | None = None
    config: dict[str, Any] | None = None


class SyncPushRequest(BaseModel):
    turns: list[dict[str, Any]] = Field(default_factory=list)


class SupportMessageRequest(BaseModel):
    name: str = ""
    email: str = ""
    message: str = Field(min_length=1)
    session_id: str = ""


app = FastAPI(title="Personal Edge AI Backend", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    # Header-based auth only (API key / bearer). No cookie auth, so credentials are not needed —
    # this avoids the unsafe "* origins + credentials" combination.
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(devices_router)
app.include_router(memory_router)

# Device-scoped (bearer) and admin (X-Admin-Key) routes; auth enforced by route dependencies, so
# they bypass the legacy shared-key middleware. Specific prefixes so legacy /v1/memory/{prefetch,
# sync,<id>} routes are unaffected.
_BEARER_OR_ADMIN_PREFIXES = (*DEVICE_SCOPED_PREFIXES, *MEMORY_DEVICE_SCOPED_PREFIXES)


@app.middleware("http")
async def api_key_auth_middleware(request: Request, call_next):
    path = request.url.path
    if path in PUBLIC_PATHS or path.startswith("/static/"):
        return await call_next(request)

    # Device-scoped / admin routes authenticate via their own dependencies (bearer token /
    # X-Admin-Key) — NOT the legacy shared key.
    if any(path.startswith(prefix) for prefix in _BEARER_OR_ADMIN_PREFIXES):
        return await call_next(request)

    # Legacy + bootstrap routes (memory/sync/config/support + device registration) still use the
    # shared API key. This is a bounded compatibility window: new durable/device-scoped data must
    # move to bearer-token auth. The shared key must be rotated in production and retired once the
    # remaining legacy memory routes are migrated (see docs/backend-stage1.md).
    api_key = request.headers.get("x-api-key") or request.headers.get("authorization", "").removeprefix("Bearer ").strip()
    if not api_key or api_key != settings.API_KEY:
        return JSONResponse(status_code=401, content={"detail": "Invalid or missing API key"})

    return await call_next(request)


def get_provider():
    return get_memory_provider()


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


def _detect_lan_ip() -> str | None:
    import socket

    candidates: list[str] = []
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127."):
                candidates.append(ip)
    except OSError:
        pass
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            candidates.append(sock.getsockname()[0])
    except OSError:
        pass

    def is_private(ip: str) -> bool:
        if ip.startswith("192.168.") or ip.startswith("10."):
            return True
        if ip.startswith("172."):
            second = int(ip.split(".")[1])
            return 16 <= second <= 31
        return False

    for ip in candidates:
        if is_private(ip) and not ip.startswith("198."):
            return ip
    for ip in candidates:
        if not ip.startswith("127.") and not ip.startswith("198."):
            return ip
    return candidates[0] if candidates else None


@app.get("/v1/connection-hints")
async def connection_hints() -> dict[str, Any]:
    lan_ip = _detect_lan_ip()
    port = settings.PORT
    return {
        "api_key_hint": settings.API_KEY,
        "phone_url": f"http://{lan_ip}:{port}" if lan_ip else None,
        "emulator_url": f"http://10.0.2.2:{port}",
        "local_url": f"http://localhost:{port}",
        "lan_ip": lan_ip,
    }


@app.get("/v1/config")
async def get_config(provider=Depends(get_provider)) -> dict[str, Any]:
    return provider.get_config()


@app.put("/v1/config")
async def update_config(body: ConfigUpdateRequest, provider=Depends(get_provider)) -> dict[str, Any]:
    patch = body.model_dump(exclude_none=True)
    if not patch:
        raise HTTPException(status_code=400, detail="No config fields provided")
    return provider.update_config(patch)


@app.post("/v1/memory/prefetch")
async def memory_prefetch(body: PrefetchRequest, provider=Depends(get_provider)) -> dict[str, Any]:
    return provider.prefetch(body.query, top_k=body.top_k)


@app.post("/v1/memory/sync")
async def memory_sync(body: SyncTurnRequest, provider=Depends(get_provider)) -> dict[str, Any]:
    return provider.sync_turn(body.user, body.assistant, body.session_id)


@app.get("/v1/memory")
async def list_memories(limit: int = 100, provider=Depends(get_provider)) -> dict[str, Any]:
    memories = provider.list_memories(limit=limit)
    return {"memories": memories, "count": len(memories)}


@app.delete("/v1/memory/{memory_id}")
async def delete_memory(memory_id: str, provider=Depends(get_provider)) -> dict[str, Any]:
    deleted = provider.delete_memory(memory_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Memory not found")
    return {"deleted": True, "id": memory_id}


@app.post("/v1/files/upload")
async def upload_file(file: UploadFile = File(...), provider=Depends(get_provider)) -> dict[str, Any]:
    if not file.filename:
        raise HTTPException(status_code=400, detail="Filename is required")

    suffix = Path(file.filename).suffix.lower()
    if suffix not in {".pdf", ".txt", ".md", ".markdown"}:
        raise HTTPException(status_code=400, detail="Supported types: pdf, txt, md")

    settings.files_dir.mkdir(parents=True, exist_ok=True)
    dest = settings.files_dir / Path(file.filename).name

    async with aiofiles.open(dest, "wb") as out:
        while chunk := await file.read(1024 * 1024):
            await out.write(chunk)

    try:
        result = provider.ingest_file(dest, original_name=file.filename)
    except Exception as exc:
        dest.unlink(missing_ok=True)
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return {"uploaded": True, **result}


@app.get("/v1/files")
async def list_files(provider=Depends(get_provider)) -> dict[str, Any]:
    files = provider.list_files()
    return {"files": files, "count": len(files)}


@app.delete("/v1/files/{filename}")
async def delete_file(filename: str, provider=Depends(get_provider)) -> dict[str, Any]:
    path = settings.files_dir / Path(filename).name
    if not path.exists() or not path.is_file():
        raise HTTPException(status_code=404, detail="File not found")
    path.unlink()
    return {"deleted": True, "name": path.name}


@app.post("/v1/sync/pull")
async def sync_pull(provider=Depends(get_provider)) -> dict[str, Any]:
    return provider.export_for_device()


@app.post("/v1/sync/push")
async def sync_push(body: SyncPushRequest, provider=Depends(get_provider)) -> dict[str, Any]:
    return provider.import_device_turns(body.turns)


@app.post("/v1/sync/merge")
async def sync_merge(body: SyncMergeRequest, provider=Depends(get_provider)) -> dict[str, Any]:
    """Merge state from cloud mirror or peer backend."""
    return provider.pull_sync(body.model_dump(exclude_none=True))


@app.post("/v1/sync/export")
async def sync_export(provider=Depends(get_provider)) -> dict[str, Any]:
    """Export full state for cloud mirror."""
    return provider.push_sync()


@app.post("/v1/sync/mirror")
async def sync_mirror(provider=Depends(get_provider)) -> dict[str, Any]:
    from memory.cloud_mirror import push_to_mirror

    payload = provider.push_sync()
    result = await push_to_mirror(payload)
    return {"exported": True, "mirror": result}


@app.post("/v1/support/message")
async def support_message(body: SupportMessageRequest) -> dict[str, Any]:
    settings.DATA_DIR.mkdir(parents=True, exist_ok=True)
    inbox = settings.DATA_DIR / "support_messages.jsonl"
    entry = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "name": body.name.strip(),
        "email": body.email.strip(),
        "message": body.message.strip(),
        "session_id": body.session_id.strip(),
    }
    with inbox.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(entry, ensure_ascii=False) + "\n")
    return {"success": True, "id": entry["timestamp"]}


@app.get("/v1/support/messages")
async def list_support_messages(limit: int = 50) -> dict[str, Any]:
    inbox = settings.DATA_DIR / "support_messages.jsonl"
    if not inbox.exists():
        return {"messages": [], "count": 0}
    lines = inbox.read_text(encoding="utf-8").splitlines()
    messages = [json.loads(line) for line in lines[-limit:] if line.strip()]
    messages.reverse()
    return {"messages": messages, "count": len(messages)}


@app.get("/")
async def home_page() -> FileResponse:
    return _serve_web_page("index.html")


@app.get("/setup")
async def setup_page() -> FileResponse:
    return _serve_web_page("setup.html")


@app.get("/admin")
async def admin_dashboard() -> FileResponse:
    return _serve_web_page("admin.html")


def _serve_web_page(filename: str) -> FileResponse:
    page_path = WEB_DIR / filename
    if not page_path.exists():
        raise HTTPException(status_code=404, detail=f"Page not found: {filename}")
    return FileResponse(page_path)


def _load_release_meta() -> dict[str, Any]:
    if settings.release_meta_path.exists():
        return json.loads(settings.release_meta_path.read_text(encoding="utf-8-sig"))
    return {}


def _load_app_version_meta() -> dict[str, Any]:
    path = settings.DATA_DIR / "app_version.json"
    if not path.exists():
        path = Path(__file__).resolve().parent / "data" / "app_version.json"
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8-sig"))
    return {}


def _resolve_apk_download_url() -> str | None:
    if settings.APK_DOWNLOAD_URL.strip():
        return settings.APK_DOWNLOAD_URL.strip()
    url = _load_app_version_meta().get("download_url")
    return url.strip() if isinstance(url, str) and url.strip() else None


def _apk_info_payload() -> dict[str, Any]:
    apk = settings.apk_path
    release_meta = _load_release_meta()
    app_meta = _load_app_version_meta()
    local_available = apk.exists() and apk.stat().st_size > 0
    download_url = _resolve_apk_download_url()
    version = release_meta.get("version") or app_meta.get("version", "1.0")
    updated_at = release_meta.get("updated_at") or app_meta.get("updated_at")

    if local_available:
        stat = apk.stat()
        return {
            "available": True,
            "source": "local",
            "filename": apk.name,
            "size_bytes": stat.st_size,
            "version": version,
            "updated_at": updated_at or datetime.fromtimestamp(stat.st_mtime, tz=timezone.utc).isoformat(),
            "download_url": "/download/apk",
        }

    if download_url:
        return {
            "available": True,
            "source": "external",
            "filename": app_meta.get("apk_filename", "personal-edge-ai.apk"),
            "size_bytes": app_meta.get("apk_size_bytes") or release_meta.get("size_bytes"),
            "version": version,
            "updated_at": updated_at,
            "download_url": download_url,
        }

    return {
        "available": False,
        "version": version,
        "updated_at": updated_at,
        "message": "No APK published yet. Run scripts\\publish-apk.ps1 on your PC.",
    }


@app.get("/v1/app/version")
async def app_version() -> dict[str, Any]:
    info = _apk_info_payload()
    app_meta = _load_app_version_meta()
    return {
        "version": info.get("version", "1.0"),
        "version_code": app_meta.get("version_code"),
        "updated_at": info.get("updated_at"),
        "apk_available": info.get("available", False),
        "apk_size_bytes": info.get("size_bytes"),
        "download_url": info.get("download_url"),
        "notes": app_meta.get("notes"),
    }


@app.get("/download/apk/info")
async def apk_info() -> dict[str, Any]:
    return _apk_info_payload()


@app.get("/download/apk")
async def download_apk():
    apk = settings.apk_path
    if apk.exists() and apk.stat().st_size > 0:
        return FileResponse(
            apk,
            media_type="application/vnd.android.package-archive",
            filename="personal-edge-ai.apk",
        )
    external = _resolve_apk_download_url()
    if external:
        return RedirectResponse(external, status_code=302)
    raise HTTPException(
        status_code=404,
        detail="APK not available. Run scripts\\publish-apk.ps1 on your PC first.",
    )


if WEB_DIR.exists():
    app.mount("/static", StaticFiles(directory=WEB_DIR), name="static")


@app.on_event("startup")
async def startup() -> None:
    settings.DATA_DIR.mkdir(parents=True, exist_ok=True)
    settings.files_dir.mkdir(parents=True, exist_ok=True)
    settings.releases_dir.mkdir(parents=True, exist_ok=True)
    # Memory provider loads lazily on first /v1/* request (avoids slow HF download at boot).


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host=settings.HOST, port=settings.PORT, reload=True)
