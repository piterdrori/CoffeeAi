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
