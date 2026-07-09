"""Stage 5: deterministic memory learning — extraction, consent, novelty, status rules.

Pure stdlib. No LLM, no embeddings, no FastAPI/Supabase coupling.
Proposals are always status=proposed. Context retrieval must never call this module to write.
"""
from __future__ import annotations

import re
from typing import Any

from memory_store import normalize_text

LEARNING_VERSION = "hermes-learning-v1"

ALLOWED_TRANSITIONS: dict[str, frozenset[str]] = {
    "proposed": frozenset({"approved", "rejected", "deleted"}),
    "approved": frozenset({"superseded", "deleted"}),
    "rejected": frozenset({"deleted"}),
    "superseded": frozenset({"deleted"}),
    "deleted": frozenset(),
}

_SECRET_PATTERNS = (
    re.compile(r"(?i)\b(password|passwd|api[_-]?key|secret|token|bearer)\b\s*[:=]"),
    re.compile(r"(?i)\b(sk-|sb_secret_|sb_publishable_|eyJ)[A-Za-z0-9_\-]{8,}"),
    re.compile(r"(?i)\b\d{13,19}\b"),  # crude payment-card-like
)

_SENSITIVE_TERMS = frozenset({
    "health", "medical", "diagnosis", "ssn", "social security", "passport",
    "bank", "credit card", "salary", "lawyer", "attorney", "lawsuit",
})

_TEMP_COMMANDS = frozenset({
    "remind me later", "for now", "just this once", "temporary", "one time",
    "don't remember", "do not remember", "forget that",
})

_PREF_PATTERNS = [
    (re.compile(r"(?i)\bi\s+prefer\s+(.+)"), "preference"),
    (re.compile(r"(?i)\bi\s+like\s+(.+)"), "preference"),
    (re.compile(r"(?i)\bi\s+usually\s+(.+)"), "preference"),
    (re.compile(r"(?i)\bdo\s+not\s+recommend\s+(.+)"), "preference"),
    (re.compile(r"(?i)\bdon't\s+recommend\s+(.+)"), "preference"),
    (re.compile(r"(?i)\bmy\s+favorite\s+(.+)"), "preference"),
]
_PROFILE_PATTERNS = [
    (re.compile(r"(?i)\bmy\s+(?:machine\s+)?model\s+is\s+(.+)"), "profile"),
    (re.compile(r"(?i)\bmy\s+name\s+is\s+(.+)"), "profile"),
    (re.compile(r"(?i)\bi\s+am\s+(.+)"), "profile"),
]
_EPISODIC_PATTERNS = [
    (re.compile(r"(?i)\b(.+)\s+(?:clogged|blocked|jammed|failed|broke)\s+(?:yesterday|today|this morning|last night|.+)"), "episodic"),
    (re.compile(r"(?i)\byesterday\s+(.+)"), "episodic"),
]
_SAFETY_PATTERNS = [
    (re.compile(r"(?i)\bdo\s+not\s+(.+)"), "safety"),
    (re.compile(r"(?i)\bnever\s+(.+)"), "safety"),
    (re.compile(r"(?i)\balways\s+avoid\s+(.+)"), "safety"),
]
_PROCEDURAL_PATTERNS = [
    (re.compile(r"(?i)\bi\s+(?:always|usually)\s+(?:clean|descale|rinse)\s+(.+)"), "procedural"),
]

_SPECULATION = re.compile(
    r"(?i)\b(maybe|might|perhaps|i think|probably|could be|seems like|guess)\b"
)
_QUESTION = re.compile(r"\?\s*$")


def validate_status_transition(current: str, new: str) -> bool:
    return new in ALLOWED_TRANSITIONS.get(current, frozenset())


def looks_like_secret(text: str) -> bool:
    return any(p.search(text or "") for p in _SECRET_PATTERNS)


def assess_sensitivity(text: str) -> str:
    norm = normalize_text(text)
    if any(t in norm for t in _SENSITIVE_TERMS):
        return "sensitive"
    return "normal"


def is_temporary_request(text: str) -> bool:
    norm = normalize_text(text)
    return any(t in norm for t in _TEMP_COMMANDS)


def normalize_subject(text: str) -> str:
    """Derive a short subject key for novelty/duplicate checks."""
    norm = normalize_text(text)
    # Drop leading pronouns / prefer verbs for stability
    norm = re.sub(r"^(i prefer|i like|i usually|do not recommend|don't recommend|my favorite|my name is|my model is)\s+", "", norm)
    tokens = [t for t in norm.split() if len(t) > 1][:8]
    return " ".join(tokens) if tokens else norm[:64]


def classify_candidate_type(text: str, category_hint: str | None = None) -> str | None:
    if category_hint in ("profile", "preference", "semantic", "episodic", "procedural", "safety"):
        # Still require it looks like an explicit statement unless safety/preference hint
        if category_hint in ("preference", "profile", "safety", "episodic", "procedural"):
            return category_hint
    for patterns, default in (
        (_SAFETY_PATTERNS, "safety"),
        (_PREF_PATTERNS, "preference"),
        (_PROFILE_PATTERNS, "profile"),
        (_PROCEDURAL_PATTERNS, "procedural"),
        (_EPISODIC_PATTERNS, "episodic"),
    ):
        for pat, typ in patterns:
            if pat.search(text or ""):
                return typ
    # Explicit category_hint semantic only if statement-like
    if category_hint == "semantic" and text and not _QUESTION.search(text.strip()):
        return "semantic"
    return None


def identify_candidate_facts(payload: dict[str, Any]) -> list[dict[str, Any]]:
    """Extract explicit candidate facts from compact learning payload. No LLM."""
    facts: list[dict[str, Any]] = []
    seen: set[str] = set()

    def add(text: str, hint: str | None, reason: str) -> None:
        text = re.sub(r"\s+", " ", (text or "").strip())
        if not text or len(text) < 4:
            return
        if _QUESTION.search(text):
            return
        if _SPECULATION.search(text):
            return
        if is_temporary_request(text):
            return
        typ = classify_candidate_type(text, hint)
        if not typ:
            return
        key = normalize_text(text)
        if key in seen:
            return
        seen.add(key)
        facts.append({
            "type": typ,
            "content": text[:2000],
            "normalized_subject": normalize_subject(text),
            "extraction_reason": reason,
            "sensitivity": assess_sensitivity(text),
        })

    for item in payload.get("explicit_user_statements") or []:
        if isinstance(item, dict):
            add(item.get("text") or "", item.get("category_hint"), "explicit_user_statement")
        elif isinstance(item, str):
            add(item, None, "explicit_user_statement")

    # Only harvest strongly patterned lines from user_message_summary (not assistant)
    user_sum = payload.get("user_message_summary") or ""
    for line in re.split(r"[\n;.]+", user_sum):
        add(line.strip(), None, "user_message_summary")

    return facts


_PREF_TOPIC_TERMS = frozenset({
    "milk", "oat", "soy", "almond", "dairy", "coffee", "espresso", "temperature",
    "grind", "dose", "strong", "weak", "sweet", "bitter", "dark", "light",
})


def preference_topic_key(text: str) -> str | None:
    """Stable topic key for preference updates (e.g. milk preference)."""
    tokens = set(normalize_text(text).split())
    if "milk" in tokens or tokens & {"oat", "soy", "almond", "dairy"}:
        return "milk_preference"
    if tokens & {"strong", "weak", "bitter", "sweet"} and ("coffee" in tokens or "espresso" in tokens):
        return "coffee_strength"
    overlap = tokens & _PREF_TOPIC_TERMS
    if overlap:
        return "pref:" + "_".join(sorted(overlap)[:3])
    return None


def assess_novelty(
    candidate: dict[str, Any],
    existing: list[dict[str, Any]],
) -> dict[str, Any]:
    """Compare candidate against existing proposed/approved memories for the device."""
    c_norm = normalize_text(candidate.get("content") or "")
    c_subj = normalize_text(candidate.get("normalized_subject") or "")
    c_topic = preference_topic_key(candidate.get("content") or "")
    best = None
    for mem in existing:
        if mem.get("status") not in ("proposed", "approved"):
            continue
        if mem.get("deleted_at") is not None:
            continue
        m_norm = normalize_text(mem.get("content") or "")
        meta = mem.get("metadata") or {}
        m_subj = normalize_text(meta.get("normalized_subject") or normalize_subject(mem.get("content") or ""))
        if not m_norm:
            continue
        if c_norm == m_norm:
            return {"outcome": "duplicate", "existing_id": mem["id"], "existing": mem}
        # near-duplicate: high token overlap
        ct, mt = set(c_norm.split()), set(m_norm.split())
        if ct and mt:
            jacc = len(ct & mt) / len(ct | mt)
            if jacc >= 0.9:
                return {"outcome": "near_duplicate", "existing_id": mem["id"], "existing": mem}
        m_topic = preference_topic_key(mem.get("content") or "")
        same_subject = bool(
            (c_subj and m_subj and (c_subj == m_subj or c_subj in m_subj or m_subj in c_subj))
            or (c_topic and m_topic and c_topic == m_topic)
        )
        if same_subject and c_norm != m_norm:
            # Contradiction if opposing milk types / strength words
            conflict = False
            if c_topic == "milk_preference" and m_topic == "milk_preference":
                conflict = True
            best = {
                "outcome": "update",
                "existing_id": mem["id"],
                "existing": mem,
                "conflict": conflict,
            }
    if best:
        return best
    return {"outcome": "new", "existing_id": None, "existing": None}


def build_memory_proposals(
    *,
    payload: dict[str, Any],
    existing_memories: list[dict[str, Any]],
) -> dict[str, Any]:
    """Build proposed memory rows (not yet persisted). Always status=proposed."""
    consent = payload.get("consent") or {}
    allow = bool(consent.get("allow_memory_proposals", True))
    allow_sensitive = bool(consent.get("allow_sensitive_memory", False))

    if not allow:
        return {
            "proposals": [],
            "proposals_created": 0,
            "duplicates_skipped": 0,
            "sensitive_skipped": 0,
            "proposals_skipped": 1,
            "consent_blocked": True,
            "reasons": ["consent_disabled"],
            "learning_version": LEARNING_VERSION,
        }

    # Reject raw audio / oversized transcript fields if present
    if payload.get("raw_audio") or payload.get("audio"):
        return {
            "proposals": [],
            "proposals_created": 0,
            "duplicates_skipped": 0,
            "sensitive_skipped": 0,
            "proposals_skipped": 1,
            "consent_blocked": False,
            "reasons": ["raw_audio_rejected"],
            "learning_version": LEARNING_VERSION,
        }
    transcript = payload.get("raw_transcript") or payload.get("transcript")
    if transcript and len(str(transcript)) > 4000:
        return {
            "proposals": [],
            "proposals_created": 0,
            "duplicates_skipped": 0,
            "sensitive_skipped": 0,
            "proposals_skipped": 1,
            "consent_blocked": False,
            "reasons": ["raw_transcript_rejected"],
            "learning_version": LEARNING_VERSION,
        }

    facts = identify_candidate_facts(payload)
    proposals: list[dict[str, Any]] = []
    duplicates_skipped = 0
    sensitive_skipped = 0
    reasons: list[str] = []

    request_id = payload.get("request_id")
    session_id = payload.get("session_id")
    turn_id = payload.get("turn_id")
    language = payload.get("language") or "en"

    for fact in facts:
        if looks_like_secret(fact["content"]):
            sensitive_skipped += 1
            reasons.append("secret_or_token_rejected")
            continue
        if fact["sensitivity"] == "sensitive" and not allow_sensitive:
            sensitive_skipped += 1
            reasons.append("sensitive_blocked")
            continue

        novelty = assess_novelty(fact, existing_memories)
        if novelty["outcome"] in ("duplicate", "near_duplicate"):
            duplicates_skipped += 1
            reasons.append(novelty["outcome"])
            continue

        importance = 0.7 if fact["type"] == "safety" else 0.55
        confidence = 0.85 if fact["extraction_reason"] == "explicit_user_statement" else 0.7
        meta: dict[str, Any] = {
            "normalized_subject": fact["normalized_subject"],
            "source_turn_id": turn_id,
            "request_id": request_id,
            "consent_state": {
                "allow_memory_proposals": allow,
                "allow_sensitive_memory": allow_sensitive,
            },
            "extraction_reason": fact["extraction_reason"],
            "learning_version": LEARNING_VERSION,
            "novelty": novelty["outcome"],
        }
        if novelty["outcome"] == "update" and novelty.get("existing_id"):
            meta["supersedes_memory_id"] = novelty["existing_id"]
            meta["conflict"] = bool(novelty.get("conflict"))

        proposals.append({
            "type": fact["type"],
            "content": fact["content"],
            "confidence": confidence,
            "importance": importance,
            "status": "proposed",
            "source": "learning",
            "source_session_id": session_id,
            "language": language,
            "sensitivity": fact["sensitivity"],
            "metadata": meta,
        })

    return {
        "proposals": proposals,
        "proposals_created": len(proposals),
        "duplicates_skipped": duplicates_skipped,
        "sensitive_skipped": sensitive_skipped,
        "proposals_skipped": duplicates_skipped + sensitive_skipped,
        "consent_blocked": False,
        "reasons": sorted(set(reasons)),
        "learning_version": LEARNING_VERSION,
    }


def propose_consolidation(episodes: list[dict[str, Any]]) -> dict[str, Any] | None:
    """Group repeated approved episodic memories into a proposed semantic summary. Never auto-approve."""
    by_subj: dict[str, list[dict[str, Any]]] = {}
    for ep in episodes:
        if ep.get("type") != "episodic" or ep.get("status") != "approved":
            continue
        meta = ep.get("metadata") or {}
        subj = normalize_text(meta.get("normalized_subject") or normalize_subject(ep.get("content") or ""))
        if not subj:
            continue
        by_subj.setdefault(subj, []).append(ep)

    for subj, items in by_subj.items():
        if len(items) < 3:
            continue
        newest = max(items, key=lambda r: r.get("updated_at") or r.get("created_at") or "")
        content = f"Recurring observation ({len(items)} times): {subj}"
        return {
            "type": "semantic",
            "content": content,
            "confidence": 0.75,
            "importance": 0.6,
            "status": "proposed",
            "source": "consolidation",
            "sensitivity": "normal",
            "metadata": {
                "normalized_subject": subj,
                "extraction_reason": "episodic_consolidation",
                "learning_version": LEARNING_VERSION,
                "consolidation_of": [i["id"] for i in items],
                "occurrence_count": len(items),
                "newest_source_id": newest.get("id"),
            },
        }
    return None
