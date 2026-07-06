from __future__ import annotations

import hashlib
import re
from pathlib import Path
from typing import Any

from pypdf import PdfReader

from config import settings


def chunk_text(text: str, chunk_size: int | None = None, overlap: int | None = None) -> list[str]:
    size = chunk_size or settings.CHUNK_SIZE
    overlap_size = overlap or settings.CHUNK_OVERLAP
    text = re.sub(r"\s+", " ", text.strip())
    if not text:
        return []

    if len(text) <= size:
        return [text]

    chunks: list[str] = []
    start = 0
    while start < len(text):
        end = start + size
        chunk = text[start:end].strip()
        if chunk:
            chunks.append(chunk)
        if end >= len(text):
            break
        start = max(end - overlap_size, start + 1)
    return chunks


def read_pdf(path: Path) -> str:
    reader = PdfReader(str(path))
    pages = [page.extract_text() or "" for page in reader.pages]
    return "\n\n".join(pages)


def read_text_file(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def extract_text(path: Path) -> str:
    suffix = path.suffix.lower()
    if suffix == ".pdf":
        return read_pdf(path)
    if suffix in {".txt", ".md", ".markdown"}:
        return read_text_file(path)
    raise ValueError(f"Unsupported file type: {suffix}")


def make_chunk_id(source: str, index: int, content: str) -> str:
    digest = hashlib.sha256(f"{source}:{index}:{content[:120]}".encode()).hexdigest()[:16]
    return f"{source}::{index}::{digest}"


def build_chunk_records(
    path: Path,
    *,
    chunk_size: int | None = None,
    overlap: int | None = None,
    extra_metadata: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    text = extract_text(path)
    chunks = chunk_text(text, chunk_size=chunk_size, overlap=overlap)
    source = path.name
    records: list[dict[str, Any]] = []

    for index, content in enumerate(chunks):
        metadata = {
            "source": source,
            "source_path": str(path),
            "chunk_index": index,
            "kind": "file",
            **(extra_metadata or {}),
        }
        records.append(
            {
                "id": make_chunk_id(source, index, content),
                "content": content,
                "metadata": metadata,
            }
        )
    return records
