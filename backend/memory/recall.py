from __future__ import annotations

from typing import Any, Protocol


class VectorCollection(Protocol):
    def query(
        self,
        *,
        query_embeddings: list[list[float]],
        n_results: int,
        where: dict[str, Any] | None = None,
        include: list[str] | None = None,
    ) -> dict[str, Any]: ...


def normalize_results(raw: dict[str, Any]) -> list[dict[str, Any]]:
    ids = (raw.get("ids") or [[]])[0]
    documents = (raw.get("documents") or [[]])[0]
    metadatas = (raw.get("metadatas") or [[]])[0]
    distances = (raw.get("distances") or [[]])[0]

    results: list[dict[str, Any]] = []
    for idx, doc_id in enumerate(ids):
        results.append(
            {
                "id": doc_id,
                "content": documents[idx] if idx < len(documents) else "",
                "metadata": metadatas[idx] if idx < len(metadatas) else {},
                "score": 1.0 - distances[idx] if idx < len(distances) else 0.0,
            }
        )
    return results


def semantic_search(
    collection: VectorCollection,
    query_embedding: list[float],
    *,
    top_k: int = 8,
    where: dict[str, Any] | None = None,
) -> list[dict[str, Any]]:
    raw = collection.query(
        query_embeddings=[query_embedding],
        n_results=top_k,
        where=where,
        include=["documents", "metadatas", "distances"],
    )
    return normalize_results(raw)


def rank_by_score(chunks: list[dict[str, Any]], *, min_score: float = 0.0) -> list[dict[str, Any]]:
    filtered = [chunk for chunk in chunks if chunk.get("score", 0.0) >= min_score]
    return sorted(filtered, key=lambda item: item.get("score", 0.0), reverse=True)
