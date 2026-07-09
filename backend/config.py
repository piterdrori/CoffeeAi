from pathlib import Path
import os

from pydantic_settings import BaseSettings, SettingsConfigDict

_DEFAULT_DATA_DIR = (
    Path("/tmp/edge-ai-data") if os.getenv("VERCEL") == "1" else Path(__file__).resolve().parent / "data"
)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    API_KEY: str = "dev-api-key-change-me"
    DATA_DIR: Path = _DEFAULT_DATA_DIR
    HOST: str = "0.0.0.0"
    PORT: int = 8080
    CORS_ORIGINS: list[str] = ["*"]

    EMBEDDING_MODEL: str = "sentence-transformers/all-MiniLM-L6-v2"
    CHUNK_SIZE: int = 512
    CHUNK_OVERLAP: int = 64
    PREFETCH_TOP_K: int = 8

    # No prompt is baked into code. The entire LLM persona, rules, and limits live in the
    # editable backend config (backend/data/config.json). These stay empty so nothing in
    # code directs how the LLM talks.
    DEFAULT_SYSTEM_PROMPT: str = ""
    DEFAULT_TONE: str = ""
    DEFAULT_RULES: list[str] = []
    # Unused for on-device inference (the phone runs the bundled Gemma model via LiteRT).
    # Kept only for optional server-side LLM setups; left empty so nothing is implied.
    DEFAULT_MODEL: str = ""
    DEFAULT_MODEL_PROVIDER: str = ""

    # External APK download (GitHub Releases, etc.) when not stored on this server.
    APK_DOWNLOAD_URL: str = ""

    # --- Stage 1: durable device identity (Supabase/Postgres) -------------------------------------
    # Server-only. Never shipped to Android. When both are set, device data is stored durably in
    # Supabase (PostgREST via service role); otherwise a non-durable in-memory store is used so the
    # API still boots for local dev / tests.
    SUPABASE_URL: str = ""
    SUPABASE_SERVICE_ROLE_KEY: str = ""
    # Server-side pepper mixed into the device-token hash (defense in depth if the DB leaks). Change
    # in production. Never sent to the client.
    DEVICE_TOKEN_SIGNING_SECRET: str = "dev-device-pepper-change-me"
    # Best-effort per-instance bootstrap rate limit for POST /v1/devices/register.
    DEVICE_REGISTER_MAX_PER_WINDOW: int = 10
    DEVICE_REGISTER_WINDOW_SECONDS: int = 600

    # --- Stage 2: memory foundation --------------------------------------------------------------
    # Server-only key that authorizes knowledge-document writes. Must NOT be a device bearer token
    # and must NOT be the shared APK key. Empty disables the knowledge write path.
    KNOWLEDGE_ADMIN_KEY: str = ""
    # Memory Context Packet bounds.
    MEMORY_QUERY_MAX_CHARS: int = 2000
    MEMORY_SESSION_ID_MAX_CHARS: int = 128
    MEMORY_SUMMARY_MAX_CHARS: int = 4000
    MEMORY_CONTENT_MAX_CHARS: int = 4000
    MEMORY_BUDGET_MIN: int = 200
    MEMORY_BUDGET_MAX: int = 1200

    # --- Stage 6A-1: Control Center admin session (server-only; never sent to Android) ----------
    # Empty password or session secret disables login (fail-closed in production).
    CONTROL_CENTER_PASSWORD: str = ""
    CONTROL_CENTER_USERNAME: str = ""  # optional; empty = password-only
    ADMIN_SESSION_SECRET: str = ""
    ADMIN_SESSION_TTL_SECONDS: int = 8 * 60 * 60  # 8 hours
    ADMIN_LOGIN_MAX_FAILURES: int = 8
    ADMIN_LOGIN_WINDOW_SECONDS: int = 600
    # None = Secure cookies when VERCEL=1 or COFFEEAI_ENV=production; False for local TestClient.
    ADMIN_COOKIE_SECURE: bool | None = None
    ADMIN_COOKIE_SAMESITE: str = "lax"

    @property
    def supabase_enabled(self) -> bool:
        return bool(self.SUPABASE_URL.strip() and self.SUPABASE_SERVICE_ROLE_KEY.strip())

    @property
    def chroma_dir(self) -> Path:
        return self.DATA_DIR / "chroma"

    @property
    def files_dir(self) -> Path:
        return self.DATA_DIR / "files"

    @property
    def config_path(self) -> Path:
        return self.DATA_DIR / "config.json"

    @property
    def sync_path(self) -> Path:
        return self.DATA_DIR / "sync_state.json"

    @property
    def releases_dir(self) -> Path:
        return self.DATA_DIR / "releases"

    @property
    def apk_path(self) -> Path:
        return self.releases_dir / "personal-edge-ai.apk"

    @property
    def release_meta_path(self) -> Path:
        return self.releases_dir / "release.json"


settings = Settings()
