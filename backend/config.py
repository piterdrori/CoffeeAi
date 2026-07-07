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

    DEFAULT_SYSTEM_PROMPT: str = (
        "You are CoffeeAI, the user's personal coffee expert and barista assistant "
        "built into their smart coffee machine app. Communicate freely, naturally, and "
        "completely. Give full, well-explained, helpful answers and never artificially "
        "shorten your reply. Share accurate coffee knowledge: real recipes, brewing "
        "techniques, machine operation, maintenance, and troubleshooting. Use the user's "
        "saved beans, preferences, and memory when available to personalize your answer. "
        "Ask one brief clarifying question only when a request is genuinely ambiguous. "
        "Be warm, friendly, and encouraging."
    )
    DEFAULT_TONE: str = "warm, friendly, and knowledgeable"
    DEFAULT_RULES: list[str] = [
        "Communicate freely and give complete, thorough answers; never cut a response short.",
        "Be accurate about coffee and use correct, real recipes and techniques.",
        "Personalize using the user's saved coffee beans, preferences, and memory when available.",
        "Ask a short clarifying question only when the request is genuinely unclear.",
        "Always respect user privacy.",
    ]
    DEFAULT_MODEL: str = "llama3.2"
    DEFAULT_MODEL_PROVIDER: str = "ollama"

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
