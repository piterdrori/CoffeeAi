from __future__ import annotations

import json
import os
import uuid
from abc import ABC, abstractmethod
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import chromadb
from chromadb.config import Settings as ChromaSettings

from config import settings
from memory.ingest import build_chunk_records
from memory.recall import semantic_search


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class MemoryProvider(ABC):
    """Hermes-inspired memory provider interface."""

    @abstractmethod
    def prefetch(self, query: str, *, top_k: int | None = None) -> dict[str, Any]:
        """Return relevant memory chunks plus runtime config."""

    @abstractmethod
    def sync_turn(self, user: str, assistant: str, session_id: str) -> dict[str, Any]:
        """Persist a conversation turn into long-term memory."""

    @abstractmethod
    def ingest_file(self, file_path: Path, *, original_name: str | None = None) -> dict[str, Any]:
        """Chunk, embed, and store a file."""

    @abstractmethod
    def get_config(self) -> dict[str, Any]:
        """Return personality, model, and sync configuration."""

    @abstractmethod
    def update_config(self, patch: dict[str, Any]) -> dict[str, Any]:
        """Update persisted configuration."""

    @abstractmethod
    def list_memories(self, *, limit: int = 100) -> list[dict[str, Any]]:
        """List stored memory facts/chunks."""

    @abstractmethod
    def delete_memory(self, memory_id: str) -> bool:
        """Delete a memory record by id."""

    @abstractmethod
    def list_files(self) -> list[dict[str, Any]]:
        """List ingested files."""

    @abstractmethod
    def get_sync_state(self) -> dict[str, Any]:
        """Return sync status metadata."""

    @abstractmethod
    def pull_sync(self, payload: dict[str, Any]) -> dict[str, Any]:
        """Merge remote state into local store."""

    @abstractmethod
    def push_sync(self) -> dict[str, Any]:
        """Export local state for remote clients."""


class ChromaMemoryProvider(MemoryProvider):
    def __init__(self) -> None:
        settings.DATA_DIR.mkdir(parents=True, exist_ok=True)
        settings.files_dir.mkdir(parents=True, exist_ok=True)
        settings.chroma_dir.mkdir(parents=True, exist_ok=True)

        self._use_chroma_embeddings = os.getenv("VERCEL") == "1" or os.getenv("USE_CHROMA_EMBEDDINGS") == "1"
        if self._use_chroma_embeddings:
            from chromadb.utils import embedding_functions

            self._embed_fn = embedding_functions.DefaultEmbeddingFunction()
        else:
            from sentence_transformers import SentenceTransformer

            self._embedder = SentenceTransformer(settings.EMBEDDING_MODEL)

        self._client = chromadb.PersistentClient(
            path=str(settings.chroma_dir),
            settings=ChromaSettings(anonymized_telemetry=False),
        )
        self._collection = self._client.get_or_create_collection(
            name="personal_memory",
            metadata={"hnsw:space": "cosine"},
        )
        self._ensure_config()

    def _ensure_config(self) -> None:
        if settings.config_path.exists():
            return
        default = {
            "system_prompt": settings.DEFAULT_SYSTEM_PROMPT,
            "tone": settings.DEFAULT_TONE,
            "rules": settings.DEFAULT_RULES,
            "model": settings.DEFAULT_MODEL,
            "model_provider": settings.DEFAULT_MODEL_PROVIDER,
            "updated_at": utc_now(),
        }
        settings.config_path.write_text(json.dumps(default, indent=2), encoding="utf-8")

        if not settings.sync_path.exists():
            settings.sync_path.write_text(
                json.dumps({"last_sync_at": None, "revision": 0, "device_id": str(uuid.uuid4())}, indent=2),
                encoding="utf-8",
            )

    def _load_json(self, path: Path, default: dict[str, Any]) -> dict[str, Any]:
        if not path.exists():
            return default
        return json.loads(path.read_text(encoding="utf-8"))

    def _save_json(self, path: Path, data: dict[str, Any]) -> None:
        path.write_text(json.dumps(data, indent=2), encoding="utf-8")

    def _embed(self, texts: list[str]) -> list[list[float]]:
        if self._use_chroma_embeddings:
            return self._embed_fn(texts)
        return self._embedder.encode(texts, normalize_embeddings=True).tolist()

    def _embed_one(self, text: str) -> list[float]:
        return self._embed([text])[0]

    def get_config(self) -> dict[str, Any]:
        config = self._load_json(
            settings.config_path,
            {
                "system_prompt": settings.DEFAULT_SYSTEM_PROMPT,
                "tone": settings.DEFAULT_TONE,
                "rules": settings.DEFAULT_RULES,
                "model": settings.DEFAULT_MODEL,
                "model_provider": settings.DEFAULT_MODEL_PROVIDER,
            },
        )
        sync = self.get_sync_state()
        return {
            **config,
            "embedding_model": settings.EMBEDDING_MODEL,
            "memory_count": self._collection.count(),
            "sync": sync,
        }

    def update_config(self, patch: dict[str, Any]) -> dict[str, Any]:
        allowed = {"system_prompt", "tone", "rules", "model", "model_provider"}
        current = self._load_json(settings.config_path, {})
        for key, value in patch.items():
            if key in allowed:
                current[key] = value
        current["updated_at"] = utc_now()
        self._save_json(settings.config_path, current)
        return self.get_config()

    def prefetch(self, query: str, *, top_k: int | None = None) -> dict[str, Any]:
        k = top_k or settings.PREFETCH_TOP_K
        chunks: list[dict[str, Any]] = []
        if query.strip():
            embedding = self._embed_one(query)
            chunks = semantic_search(self._collection, embedding, top_k=k)
        config = self.get_config()
        rules = config.get("rules") or []
        personality_rules = "\n".join(f"- {rule}" for rule in rules) if isinstance(rules, list) else str(rules)
        return {
            "query": query,
            "chunks": chunks,
            "config": {
                "system_prompt": config.get("system_prompt"),
                "personality_rules": personality_rules,
                "tone": config.get("tone"),
                "rules": rules,
                "model": config.get("model"),
                "model_provider": config.get("model_provider"),
            },
        }

    def sync_turn(self, user: str, assistant: str, session_id: str) -> dict[str, Any]:
        summary = f"User: {user.strip()}\nAssistant: {assistant.strip()}".strip()
        memory_id = f"turn::{session_id}::{uuid.uuid4().hex[:12]}"
        metadata = {
            "kind": "turn",
            "session_id": session_id,
            "source": "conversation",
            "created_at": utc_now(),
        }
        embedding = self._embed_one(summary)
        self._collection.upsert(
            ids=[memory_id],
            documents=[summary],
            embeddings=[embedding],
            metadatas=[metadata],
        )
        return {"stored": True, "memory_id": memory_id, "session_id": session_id}

    def ingest_file(self, file_path: Path, *, original_name: str | None = None) -> dict[str, Any]:
        records = build_chunk_records(file_path, extra_metadata={"original_name": original_name or file_path.name})
        if not records:
            return {"ingested": False, "chunks": 0, "filename": file_path.name}

        ids = [record["id"] for record in records]
        documents = [record["content"] for record in records]
        metadatas = [record["metadata"] for record in records]
        embeddings = self._embed(documents)

        self._collection.upsert(
            ids=ids,
            documents=documents,
            embeddings=embeddings,
            metadatas=metadatas,
        )
        return {
            "ingested": True,
            "filename": file_path.name,
            "chunks": len(records),
            "ids": ids,
        }

    def list_memories(self, *, limit: int = 100) -> list[dict[str, Any]]:
        if self._collection.count() == 0:
            return []

        raw = self._collection.get(
            limit=limit,
            include=["documents", "metadatas"],
        )
        items: list[dict[str, Any]] = []
        for idx, memory_id in enumerate(raw.get("ids", [])):
            items.append(
                {
                    "id": memory_id,
                    "content": raw["documents"][idx] if raw.get("documents") else "",
                    "metadata": raw["metadatas"][idx] if raw.get("metadatas") else {},
                }
            )
        items.sort(key=lambda item: item.get("metadata", {}).get("created_at", ""), reverse=True)
        return items

    def delete_memory(self, memory_id: str) -> bool:
        existing = self._collection.get(ids=[memory_id])
        if not existing.get("ids"):
            return False
        self._collection.delete(ids=[memory_id])
        return True

    def list_files(self) -> list[dict[str, Any]]:
        files: list[dict[str, Any]] = []
        for path in sorted(settings.files_dir.iterdir()):
            if not path.is_file():
                continue
            stat = path.stat()
            files.append(
                {
                    "name": path.name,
                    "size": stat.st_size,
                    "modified_at": datetime.fromtimestamp(stat.st_mtime, tz=timezone.utc).isoformat(),
                }
            )
        return files

    def get_sync_state(self) -> dict[str, Any]:
        return self._load_json(
            settings.sync_path,
            {"last_sync_at": None, "revision": 0, "device_id": str(uuid.uuid4())},
        )

    def pull_sync(self, payload: dict[str, Any]) -> dict[str, Any]:
        incoming = payload.get("memories") or []
        if incoming:
            ids = [item["id"] for item in incoming]
            documents = [item["content"] for item in incoming]
            metadatas = [item.get("metadata", {}) for item in incoming]
            embeddings = self._embed(documents)
            self._collection.upsert(
                ids=ids,
                documents=documents,
                embeddings=embeddings,
                metadatas=metadatas,
            )

        if payload.get("config"):
            current = self._load_json(settings.config_path, {})
            current.update(payload["config"])
            current["updated_at"] = utc_now()
            self._save_json(settings.config_path, current)

        state = self.get_sync_state()
        state["revision"] = int(state.get("revision", 0)) + 1
        state["last_sync_at"] = utc_now()
        self._save_json(settings.sync_path, state)
        return {"accepted": True, "revision": state["revision"], "merged": len(incoming)}

    def push_sync(self) -> dict[str, Any]:
        memories = self.list_memories(limit=500)
        config = self._load_json(settings.config_path, {})
        state = self.get_sync_state()
        state["last_sync_at"] = utc_now()
        self._save_json(settings.sync_path, state)
        return {
            "revision": state.get("revision", 0),
            "device_id": state.get("device_id"),
            "config": config,
            "memories": memories,
            "files": self.list_files(),
        }

    def export_for_device(self) -> dict[str, Any]:
        config = self._load_json(settings.config_path, {})
        rules = config.get("rules") or []
        personality_rules = "\n".join(f"- {rule}" for rule in rules) if isinstance(rules, list) else str(rules)
        memories = []
        for item in self.list_memories(limit=500):
            meta = item.get("metadata") or {}
            created = meta.get("created_at", utc_now())
            if isinstance(created, str):
                try:
                    ts = int(datetime.fromisoformat(created.replace("Z", "+00:00")).timestamp() * 1000)
                except ValueError:
                    ts = int(datetime.now(timezone.utc).timestamp() * 1000)
            else:
                ts = int(datetime.now(timezone.utc).timestamp() * 1000)
            memories.append(
                {
                    "id": item["id"],
                    "content": item.get("content", ""),
                    "source": meta.get("source", "memory"),
                    "timestamp": ts,
                }
            )
        return {
            "config": {
                "system_prompt": config.get("system_prompt", settings.DEFAULT_SYSTEM_PROMPT),
                "personality_rules": personality_rules,
                "tone": config.get("tone", settings.DEFAULT_TONE),
            },
            "memories": memories,
        }

    def import_device_turns(self, turns: list[dict[str, Any]]) -> dict[str, Any]:
        stored = 0
        for turn in turns:
            user = turn.get("user", "")
            assistant = turn.get("assistant", "")
            session_id = turn.get("session_id", "offline")
            if user and assistant:
                self.sync_turn(user, assistant, session_id)
                stored += 1
        state = self.get_sync_state()
        state["revision"] = int(state.get("revision", 0)) + 1
        state["last_sync_at"] = utc_now()
        self._save_json(settings.sync_path, state)
        return {"success": True, "stored": stored}


_provider: ChromaMemoryProvider | None = None


def get_memory_provider() -> ChromaMemoryProvider:
    global _provider
    if _provider is None:
        _provider = ChromaMemoryProvider()
    return _provider
