"""Stage 3: deterministic memory/knowledge retrieval, ranking, and context packing.

Pure stdlib — no embeddings, no LLM calls. Query text is normalized for matching but never logged.
"""
from __future__ import annotations

import math
import re
from datetime import datetime, timezone
from typing import Any

from memory_store import estimate_tokens, normalize_text

RETRIEVAL_STRATEGY_VERSION = "deterministic-v1"
MEMORY_PACKET_VERSION = 2

# Composite score weights (documented, sum = 1.0).
WEIGHT_RELEVANCE = 0.40
WEIGHT_CONFIDENCE = 0.20
WEIGHT_RECENCY = 0.15
WEIGHT_IMPORTANCE = 0.15
WEIGHT_SOURCE_QUALITY = 0.10

GENERIC_PRODUCT = "coffeeai"
GENERIC_VERSION = "*"
MAX_SELECTED_ITEMS = 8
MAX_SAFETY = 6
MAX_KNOWLEDGE = 4
MAX_RELEVANT = 6
MAX_PROFILE = 3

_STOPWORDS = frozenset({
    "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
    "have", "has", "had", "do", "does", "did", "will", "would", "could", "should",
    "may", "might", "must", "shall", "can", "need", "to", "of", "in", "for", "on",
    "with", "at", "by", "from", "up", "about", "into", "over", "after",
    "i", "me", "my", "we", "our", "you", "your", "it", "its", "this", "that",
    "what", "which", "who", "whom", "how", "when", "where", "why", "and", "or", "but",
})

_HOW_TO_TOKENS = frozenset({
    "how", "fix", "troubleshoot", "error", "descale", "clean", "rinse", "procedure",
    "steps", "repair", "maintain", "calibrate",
})

_TRUST_SCORES = {
    "verified": 1.0,
    "manual": 0.95,
    "official": 0.95,
    "approved": 0.85,
    "qa": 0.70,
    "unknown": 0.40,
}

_SOURCE_SCORES = {
    "manual": 1.0,
    "verified": 1.0,
    "device": 0.85,
    "conversation": 0.65,
    "import": 0.75,
    "unknown": 0.40,
}

_SLOW_DECAY_TYPES = frozenset({"safety", "procedural", "profile"})
_EPISODIC_TYPE = "episodic"


def normalize_query(query: str) -> dict[str, Any]:
    """Derive matching artifacts from a query. Original query is not returned."""
    raw = re.sub(r"\s+", " ", (query or "").strip())
    lowered = raw.lower()
    match_text = re.sub(r"[^\w\s]", " ", lowered)
    match_text = re.sub(r"\s+", " ", match_text).strip()
    tokens = [t for t in match_text.split() if t and t not in _STOPWORDS and len(t) > 1]
    is_how_to = bool(_HOW_TO_TOKENS.intersection(set(tokens)) or "how to" in match_text)
    return {
        "match_text": match_text,
        "tokens": tokens,
        "is_how_to": is_how_to,
    }


def _parse_ts(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def _clamp01(value: float) -> float:
    return max(0.0, min(1.0, value))


def _recency_score(updated_at: str | None, mem_type: str) -> float:
    dt = _parse_ts(updated_at)
    if dt is None:
        return 0.5
    age_days = max(0.0, (datetime.now(timezone.utc) - dt.astimezone(timezone.utc)).total_seconds() / 86400.0)
    if mem_type in _SLOW_DECAY_TYPES:
        half_life = 365.0
    elif mem_type == _EPISODIC_TYPE:
        half_life = 30.0
    else:
        half_life = 90.0
    return _clamp01(math.exp(-age_days / half_life))


def _keyword_overlap(query_tokens: list[str], *texts: str | None) -> float:
    if not query_tokens:
        return 0.0
    hay = normalize_text(" ".join(t for t in texts if t))
    if not hay:
        return 0.0
    hay_tokens = set(hay.split())
    if not hay_tokens:
        return 0.0
    overlap = sum(1 for t in query_tokens if t in hay_tokens)
    base = overlap / len(query_tokens)
    joined = " ".join(query_tokens)
    if joined and joined in hay:
        base = max(base, 0.95)
    elif len(query_tokens) >= 2:
        phrase = " ".join(query_tokens[: min(4, len(query_tokens))])
        if phrase in hay:
            base = max(base, 0.85)
    return _clamp01(base)


def _metadata_tags(metadata: dict[str, Any] | None) -> str:
    if not metadata:
        return ""
    tags = metadata.get("tags") or metadata.get("keywords") or []
    if isinstance(tags, list):
        return " ".join(str(t) for t in tags)
    return str(tags)


def _language_compatible(item_lang: str | None, request_lang: str) -> bool:
    if not item_lang:
        return True
    return item_lang.lower()[:2] == request_lang.lower()[:2]


def _version_compatible(item_version: str | None, request_version: str | None) -> bool:
    if not item_version or not request_version:
        return True
    if item_version in (GENERIC_VERSION, "generic", "*"):
        return True
    return item_version == request_version


def filter_memory_candidates(
    memories: list[dict[str, Any]],
    *,
    language: str,
    product_version: str | None,
) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for mem in memories:
        if mem.get("deleted_at") is not None:
            continue
        if mem.get("status") != "approved":
            continue
        if mem.get("type") not in ("profile", "episodic", "semantic", "procedural", "preference", "safety"):
            continue
        if not _language_compatible(mem.get("language"), language):
            continue
        if not _version_compatible(mem.get("product_version"), product_version):
            continue
        sens = (mem.get("sensitivity") or "normal").lower()
        if sens not in ("normal", "low"):
            continue
        out.append(mem)
    return out


def _knowledge_tier(doc: dict[str, Any], product: str, locale: str, product_version: str | None) -> int | None:
    """Lower tier number = better match. None = exclude."""
    doc_product = (doc.get("product") or "").strip()
    doc_locale = (doc.get("locale") or "").strip().lower()
    doc_version = (doc.get("version") or "").strip()
    req_locale = locale.lower()[:8]

    if doc.get("status") != "active":
        return None

    locale_ok = doc_locale == req_locale or doc_locale[:2] == req_locale[:2]
    if not locale_ok:
        return None

    if doc_product == product:
        if product_version and doc_version == product_version:
            return 1
        if doc_version in (GENERIC_VERSION, "generic", "*") or not product_version:
            return 2
        if product_version and doc_version == product_version:
            return 1
        return 2 if doc_version in (GENERIC_VERSION, "generic", "*") else None

    if doc_product == GENERIC_PRODUCT and product not in ("", "generic"):
        return 3

    return None


def filter_knowledge_candidates(
    chunks: list[dict[str, Any]],
    *,
    product: str,
    locale: str,
    product_version: str | None,
) -> list[dict[str, Any]]:
    if not product or product == "generic":
        return []
    ranked: list[tuple[int, dict[str, Any]]] = []
    for chunk in chunks:
        doc = chunk.get("_document") or chunk
        tier = _knowledge_tier(doc, product, locale, product_version)
        if tier is None:
            continue
        enriched = dict(chunk)
        enriched["_tier"] = tier
        enriched["_trust_level"] = doc.get("trust_level", "unknown")
        enriched["_doc_product"] = doc.get("product")
        enriched["_doc_version"] = doc.get("version")
        ranked.append((tier, enriched))
    ranked.sort(key=lambda x: (x[0], x[1].get("chunk_index", 0)))
    return [c for _, c in ranked]


def _source_quality(mem: dict[str, Any]) -> float:
    source = (mem.get("source") or "unknown").lower()
    return _SOURCE_SCORES.get(source, _SOURCE_SCORES["unknown"])


def _type_relevance_boost(mem_type: str, relevance: float, is_how_to: bool) -> float:
    if mem_type == "safety":
        return max(relevance, 0.35)
    if mem_type == "procedural" and is_how_to:
        return _clamp01(relevance + 0.25)
    if mem_type in ("profile", "preference"):
        return _clamp01(relevance * 0.85)
    if mem_type == _EPISODIC_TYPE:
        return _clamp01(relevance + 0.05)
    return relevance


def score_memory(mem: dict[str, Any], query: dict[str, Any]) -> dict[str, Any]:
    content = mem.get("content") or ""
    summary = mem.get("summary") or ""
    tags = _metadata_tags(mem.get("metadata"))
    mem_type = mem.get("type") or "semantic"

    relevance = _keyword_overlap(query["tokens"], content, summary, tags)
    relevance = _type_relevance_boost(mem_type, relevance, query["is_how_to"])

    confidence = _clamp01(float(mem.get("confidence", 1.0)))
    recency = _recency_score(mem.get("updated_at") or mem.get("created_at"), mem_type)
    importance = _clamp01(float(mem.get("importance", 0.5)))
    if mem_type == "safety":
        importance = max(importance, 0.9)
    source_quality = _source_quality(mem)

    total = (
        relevance * WEIGHT_RELEVANCE
        + confidence * WEIGHT_CONFIDENCE
        + recency * WEIGHT_RECENCY
        + importance * WEIGHT_IMPORTANCE
        + source_quality * WEIGHT_SOURCE_QUALITY
    )

    dt = _parse_ts(mem.get("updated_at") or mem.get("created_at"))
    age_days = 0
    if dt:
        age_days = int((datetime.now(timezone.utc) - dt.astimezone(timezone.utc)).total_seconds() // 86400)

    reason = "keyword_match" if relevance >= 0.5 else "type_priority" if mem_type == "safety" else "recency_rank"
    return {
        "score": round(total, 4),
        "components": {
            "relevance": round(relevance, 4),
            "confidence": round(confidence, 4),
            "recency": round(recency, 4),
            "importance": round(importance, 4),
            "source_quality": round(source_quality, 4),
        },
        "reason_category": reason,
        "source_type": mem.get("source") or "unknown",
        "confidence": confidence,
        "age_days": age_days,
        "trust_level": None,
        "item_type": "memory",
        "memory_type": mem_type,
    }


def score_knowledge(chunk: dict[str, Any], query: dict[str, Any]) -> dict[str, Any]:
    title = chunk.get("title") or ""
    content = chunk.get("content") or ""
    tags = _metadata_tags(chunk.get("metadata"))
    trust = (chunk.get("_trust_level") or "unknown").lower()
    trust_score = _TRUST_SCORES.get(trust, _TRUST_SCORES["unknown"])

    relevance = _keyword_overlap(query["tokens"], title, content, tags)
    if query["is_how_to"]:
        relevance = _clamp01(relevance + 0.15)
    tier = chunk.get("_tier", 3)
    tier_boost = {1: 0.15, 2: 0.08, 3: 0.0}.get(tier, 0.0)
    relevance = _clamp01(relevance + tier_boost)

    confidence = trust_score
    recency = 0.85
    importance = 0.75
    source_quality = trust_score

    total = (
        relevance * WEIGHT_RELEVANCE
        + confidence * WEIGHT_CONFIDENCE
        + recency * WEIGHT_RECENCY
        + importance * WEIGHT_IMPORTANCE
        + source_quality * WEIGHT_SOURCE_QUALITY
    )

    return {
        "score": round(total, 4),
        "components": {
            "relevance": round(relevance, 4),
            "confidence": round(confidence, 4),
            "recency": round(recency, 4),
            "importance": round(importance, 4),
            "source_quality": round(source_quality, 4),
        },
        "reason_category": "product_knowledge" if tier <= 2 else "generic_knowledge",
        "source_type": "knowledge",
        "confidence": confidence,
        "age_days": 0,
        "trust_level": trust,
        "item_type": "knowledge",
        "memory_type": None,
    }


def _text_signature(text: str) -> str:
    return normalize_text(text)


def _signatures_overlap(a: str, b: str) -> bool:
    if not a or not b:
        return False
    if a == b:
        return True
    if a in b or b in a:
        return True
    ta, tb = set(a.split()), set(b.split())
    if not ta or not tb:
        return False
    inter = len(ta & tb)
    union = len(ta | tb)
    return union > 0 and (inter / union) >= 0.85


def dedupe_ranked(items: list[tuple[dict[str, Any], dict[str, Any]]]) -> list[tuple[dict[str, Any], dict[str, Any]]]:
    """Prefer higher score, then newer trust, then higher trust."""
    kept: list[tuple[dict[str, Any], dict[str, Any], str]] = []
    for item, meta in sorted(items, key=lambda x: x[1]["score"], reverse=True):
        text = item.get("content") or item.get("summary") or ""
        sig = _text_signature(text)
        if any(_signatures_overlap(sig, k[2]) for k in kept):
            continue
        kept.append((item, meta, sig))
    return [(i, m) for i, m, _ in kept]


def _truncate_to_budget(text: str, remaining: int) -> tuple[str, int]:
    est = estimate_tokens(text)
    if est <= remaining:
        return text, est
    max_chars = max(4, remaining * 4)
    truncated = text[:max_chars].rstrip()
    if len(truncated) < len(text):
        truncated += "…"
    return truncated, estimate_tokens(truncated)


def retrieve_and_pack_context(
    *,
    request_id: str,
    language: str,
    budget: int,
    query: str,
    product: str,
    product_version: str | None,
    summary: dict[str, Any] | None,
    memories: list[dict[str, Any]],
    knowledge_chunks: list[dict[str, Any]],
) -> tuple[dict[str, Any], dict[str, Any]]:
    q = normalize_query(query)
    filtered_mem = filter_memory_candidates(memories, language=language, product_version=product_version)
    filtered_know = filter_knowledge_candidates(
        knowledge_chunks, product=product, locale=language, product_version=product_version,
    )

    candidate_count = len(filtered_mem) + len(filtered_know) + (1 if summary else 0)

    safety_scored = [(m, score_memory(m, q)) for m in filtered_mem if m.get("type") == "safety"]
    profile_scored = [(m, score_memory(m, q)) for m in filtered_mem if m.get("type") in ("profile", "preference")]
    relevant_scored = [(m, score_memory(m, q)) for m in filtered_mem if m.get("type") in ("episodic", "semantic", "procedural")]
    knowledge_scored = [(c, score_knowledge(c, q)) for c in filtered_know]

    safety_scored.sort(key=lambda x: x[1]["score"], reverse=True)
    profile_scored.sort(key=lambda x: x[1]["score"], reverse=True)
    relevant_scored.sort(key=lambda x: x[1]["score"], reverse=True)
    knowledge_scored.sort(key=lambda x: x[1]["score"], reverse=True)

    safety_scored = dedupe_ranked(safety_scored)[:MAX_SAFETY]
    knowledge_scored = dedupe_ranked(knowledge_scored)[:MAX_KNOWLEDGE]
    relevant_scored = dedupe_ranked(relevant_scored)[:MAX_RELEVANT]
    profile_scored = dedupe_ranked(profile_scored)[:MAX_PROFILE]

    used = 0
    seen_sigs: set[str] = set()
    categories: list[str] = []
    memory_ids: list[str] = []
    knowledge_ids: list[str] = []
    audit_items: list[dict[str, Any]] = []
    scores: list[float] = []

    def pack_item(text: str, item: dict, meta: dict, category: str) -> str | None:
        nonlocal used
        if not text:
            return None
        sig = _text_signature(text)
        if sig in seen_sigs:
            return None
        remaining = budget - used
        trimmed, est = _truncate_to_budget(text, remaining)
        if est <= 0 or used + est > budget:
            return None
        seen_sigs.add(sig)
        used += est
        if category == "knowledge":
            knowledge_ids.append(item["id"])
        else:
            memory_ids.append(item["id"])
        if category not in categories:
            categories.append(category)
        scores.append(meta["score"])
        audit_items.append({
            "id": item.get("id"),
            "score": meta["score"],
            "reason_category": meta["reason_category"],
            "source_type": meta["source_type"],
            "type": meta.get("memory_type") or "knowledge",
        })
        return trimmed

    def cap_reached() -> bool:
        return (
            len(out_safety) + len(out_knowledge) + len(out_relevant) + len(out_profile) + (1 if out_summary else 0)
            >= MAX_SELECTED_ITEMS
        )

    out_safety: list[dict[str, Any]] = []
    out_summary: str | None = None
    out_knowledge: list[dict[str, Any]] = []
    out_relevant: list[dict[str, Any]] = []
    out_profile: list[dict[str, Any]] = []

    for item, meta in safety_scored:
        if cap_reached():
            break
        packed = pack_item(item["content"], item, meta, "safety")
        if packed:
            out_safety.append({"id": item["id"], "content": packed})

    if summary and summary.get("summary") and not cap_reached():
        sum_text = summary["summary"]
        sig = _text_signature(sum_text)
        if sig not in seen_sigs:
            trimmed, est = _truncate_to_budget(sum_text, budget - used)
            if est > 0 and used + est <= budget:
                seen_sigs.add(sig)
                used += est
                out_summary = trimmed
                categories.append("recent_summary")

    for item, meta in knowledge_scored:
        if cap_reached():
            break
        packed = pack_item(item.get("content") or "", item, meta, "knowledge")
        if packed:
            out_knowledge.append({"id": item["id"], "title": item.get("title"), "content": packed})

    for item, meta in relevant_scored:
        if cap_reached():
            break
        packed = pack_item(item["content"], item, meta, "relevant_memories")
        if packed:
            out_relevant.append({"id": item["id"], "type": item["type"], "content": packed})

    for item, meta in profile_scored:
        if cap_reached():
            break
        packed = pack_item(item["content"], item, meta, "profile")
        if packed:
            out_profile.append({"id": item["id"], "content": packed})

    selected_count = len(out_safety) + len(out_knowledge) + len(out_relevant) + len(out_profile) + (1 if out_summary else 0)
    score_range = {"min": min(scores), "max": max(scores)} if scores else {"min": 0.0, "max": 0.0}

    packet = {
        "request_id": request_id,
        "memory_version": MEMORY_PACKET_VERSION,
        "intent": "unknown",
        "language": language,
        "user_profile": out_profile,
        "relevant_memories": out_relevant,
        "knowledge_snippets": out_knowledge,
        "recent_summary": out_summary,
        "safety_rules": out_safety,
        "context_token_budget": budget,
        "used_token_estimate": used,
        "categories_used": categories,
        "retrieval_strategy": RETRIEVAL_STRATEGY_VERSION,
        "candidate_count": candidate_count,
        "selected_count": selected_count,
        "fallback": False,
    }
    usage = {
        "categories": categories,
        "memory_ids": memory_ids,
        "knowledge_ids": knowledge_ids,
        "used_token_estimate": used,
        "retrieval_strategy": RETRIEVAL_STRATEGY_VERSION,
        "candidate_count": candidate_count,
        "selected_count": selected_count,
        "score_range": score_range,
        "audit_items": audit_items,
        "query_len": len(query or ""),
    }
    return packet, usage
