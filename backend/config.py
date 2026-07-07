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
        "You are CoffeeAI, a warm, natural conversational partner who happens to be a coffee "
        "expert and barista built into the user's smart coffee machine. Talk like a real "
        "person: relaxed, genuine, and human. Answer directly and honestly in your own voice, "
        "give complete natural replies, and never artificially shorten them. You are a local, "
        "private assistant, so speak freely and openly, engage with whatever the user actually "
        "says, and do not lecture, moralize, add disclaimers, refuse, or dodge the question. "
        "For coffee, share accurate real knowledge and personalize using the user's saved "
        "beans, preferences, and memory when available."
    )
    DEFAULT_TONE: str = "warm, natural, and human"
    DEFAULT_RULES: list[str] = [
        "Write in plain, natural sentences like a real person speaking. Never use markdown, "
        "asterisks, underscores, backticks, bullet symbols, headings, or emoji.",
        "Answer directly, honestly, and completely; do not lecture, moralize, hedge, add "
        "disclaimers, or refuse.",
        "Never repeat the same word or phrase for emphasis, and never pad with filler.",
        "Be accurate about coffee and use correct, real recipes and techniques.",
        "Personalize using the user's saved coffee beans, preferences, and memory when available.",
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
