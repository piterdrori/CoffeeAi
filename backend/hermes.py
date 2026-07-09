"""Stage 4: Hermes — deterministic backend coordinator for memory context.

Pure stdlib. No FastAPI, no Supabase, no LLM, no embeddings. Hermes decides intent,
retrieval plan, conflict handling, and compression; Stage 3 scoring/packing remains the
ranking engine. Local Gemma remains the final answer generator.
"""
from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Any

from memory_retrieval import (
    MEMORY_PACKET_VERSION,
    RETRIEVAL_STRATEGY_VERSION,
    dedupe_ranked,
    filter_knowledge_candidates,
    filter_memory_candidates,
    normalize_query,
    retrieve_and_pack_context,
    score_knowledge,
    score_memory,
)
from memory_store import estimate_tokens, normalize_text

HERMES_VERSION = "hermes-v1"
MEMORY_PACKET_VERSION_HERMES = 3  # additive Hermes metadata; Stage 3 fields retained

INTENTS = (
    "general_chat",
    "machine_operation",
    "machine_troubleshooting",
    "cleaning_and_maintenance",
    "coffee_preparation",
    "user_preference",
    "account_or_profile",
    "safety_related",
    "unknown",
)

_SAFETY_TERMS = frozenset({
    "safety", "danger", "dangerous", "hot", "burn", "steam", "pressure", "shock",
    "never", "warning", "hazard", "scald", "electric", "voltage",
})
_TROUBLE_TERMS = frozenset({
    "error", "fault", "broken", "jam", "jammed", "leak", "leaking", "not working",
    "troubleshoot", "problem", "fail", "failed", "stuck", "noise", "alarm",
})
_CLEAN_TERMS = frozenset({
    "clean", "cleaning", "descale", "descaling", "rinse", "maintain", "maintenance",
    "flush", "purge", "backflush", "filter",
})
_PREP_TERMS = frozenset({
    "brew", "espresso", "shot", "grind", "grinder", "dose", "tamp", "milk",
    "froth", "latte", "cappuccino", "recipe", "extraction", "ratio",
})
_PREF_TERMS = frozenset({
    "prefer", "preference", "favorite", "favourite", "like", "dislike", "always",
    "usually", "my", "remember",
})
_PROFILE_TERMS = frozenset({
    "name", "profile", "account", "settings", "language", "who am i",
})
_MACHINE_TERMS = frozenset({
    "machine", "boiler", "group", "portafilter", "steam wand", "pump", "valve",
    "water tank", "brew group",
})
_OP_TERMS = frozenset({
    "how to", "turn on", "start", "stop", "operate", "operation", "use", "setup",
    "calibrate", "procedure", "steps",
})

_CONFLICT_SUBJECTS = (
    ("milk", ("oat", "soy", "almond", "dairy", "cow", "whole", "skim")),
    ("temperature", ("hot", "cold", "warm", "celsius", "fahrenheit", "degree")),
    ("grind", ("fine", "coarse", "medium")),
    ("dose", ("gram", "grams", "g ")),
    ("pressure", ("bar", "psi")),
)


def classify_intent(query: str) -> dict[str, Any]:
    """Deterministic intent classification. Never logs the raw query."""
    q = normalize_query(query)
    text = q["match_text"]
    tokens = set(q["tokens"])
    signals: list[str] = []

    def hit(label: str, terms: frozenset[str]) -> int:
        n = 0
        for t in terms:
            if " " in t:
                if t in text:
                    n += 1
                    signals.append(f"{label}:{t}")
            elif t in tokens or t in text:
                n += 1
                signals.append(f"{label}:{t}")
        return n

    safety_n = hit("safety", _SAFETY_TERMS)
    trouble_n = hit("trouble", _TROUBLE_TERMS)
    clean_n = hit("clean", _CLEAN_TERMS)
    prep_n = hit("prep", _PREP_TERMS)
    pref_n = hit("pref", _PREF_TERMS)
    profile_n = hit("profile", _PROFILE_TERMS)
    machine_n = hit("machine", _MACHINE_TERMS)
    op_n = hit("op", _OP_TERMS)

    intent = "unknown"
    confidence = 0.35

    if safety_n >= 1 and (machine_n or trouble_n or clean_n or op_n or safety_n >= 2):
        intent, confidence = "safety_related", min(0.95, 0.55 + 0.15 * safety_n)
    elif trouble_n >= 1 and (machine_n or clean_n or op_n or trouble_n >= 2):
        intent, confidence = "machine_troubleshooting", min(0.95, 0.55 + 0.12 * trouble_n)
    elif clean_n >= 1:
        intent, confidence = "cleaning_and_maintenance", min(0.92, 0.55 + 0.12 * clean_n)
    elif prep_n >= 1 and (machine_n or prep_n >= 2 or op_n):
        intent, confidence = "coffee_preparation", min(0.9, 0.5 + 0.1 * prep_n)
    elif pref_n >= 1 and not (trouble_n or clean_n):
        intent, confidence = "user_preference", min(0.88, 0.5 + 0.12 * pref_n)
    elif profile_n >= 1 and not (trouble_n or clean_n or prep_n):
        intent, confidence = "account_or_profile", min(0.85, 0.5 + 0.12 * profile_n)
    elif op_n >= 1 or (machine_n and q["is_how_to"]):
        intent, confidence = "machine_operation", min(0.88, 0.5 + 0.1 * max(op_n, machine_n))
    elif not tokens:
        intent, confidence = "general_chat", 0.6
    elif not (machine_n or prep_n or clean_n or trouble_n or safety_n):
        intent, confidence = "general_chat", 0.55

    force_safety = intent == "safety_related" or safety_n >= 1
    memory_needed = intent not in ("general_chat",) or pref_n > 0 or profile_n > 0
    if intent == "general_chat" and not (pref_n or profile_n or safety_n):
        memory_needed = False
    knowledge_needed = intent in (
        "machine_operation", "machine_troubleshooting", "cleaning_and_maintenance",
        "coffee_preparation", "safety_related",
    )

    return {
        "intent": intent,
        "confidence": round(confidence, 4),
        "matched_signals": sorted(set(signals))[:20],
        "memory_needed": memory_needed,
        "knowledge_needed": knowledge_needed,
        "force_safety": force_safety,
        "query_tokens": q["tokens"],
        "is_how_to": q["is_how_to"],
    }


def build_retrieval_plan(
    classification: dict[str, Any],
    *,
    product: str,
    product_version: str | None,
    language: str,
    token_budget: int,
) -> dict[str, Any]:
    intent = classification["intent"]
    force_safety = classification["force_safety"]

    memory_types: list[str] = []
    include_summary = False
    include_knowledge = False
    include_safety = force_safety
    max_mem = 40
    max_know = 20

    if intent == "machine_troubleshooting":
        memory_types = ["episodic", "procedural", "semantic", "safety"]
        include_summary = True
        include_knowledge = True
        include_safety = True
        max_mem, max_know = 80, 40
    elif intent == "cleaning_and_maintenance":
        memory_types = ["procedural", "semantic", "episodic", "safety"]
        include_summary = True
        include_knowledge = True
        include_safety = True
        max_mem, max_know = 60, 40
    elif intent == "machine_operation":
        memory_types = ["procedural", "semantic", "safety"]
        include_summary = True
        include_knowledge = True
        include_safety = True
    elif intent == "coffee_preparation":
        memory_types = ["preference", "semantic", "procedural", "episodic"]
        include_summary = True
        include_knowledge = True
        include_safety = force_safety
    elif intent == "user_preference":
        memory_types = ["preference", "profile", "semantic"]
        include_summary = False
        include_knowledge = False
        include_safety = force_safety
        max_mem, max_know = 30, 0
    elif intent == "account_or_profile":
        memory_types = ["profile", "preference"]
        include_summary = False
        include_knowledge = False
        include_safety = force_safety
        max_mem, max_know = 20, 0
    elif intent == "safety_related":
        memory_types = ["safety", "procedural", "semantic"]
        include_summary = True
        include_knowledge = True
        include_safety = True
        max_mem, max_know = 60, 40
    elif intent == "general_chat":
        memory_types = []
        include_summary = False
        include_knowledge = False
        include_safety = force_safety
        max_mem, max_know = 10, 0
        if classification["memory_needed"]:
            memory_types = ["preference", "profile", "semantic"]
    else:  # unknown
        memory_types = ["semantic", "procedural", "preference", "safety"]
        include_summary = True
        include_knowledge = bool(product and product != "generic")
        include_safety = True
        max_mem, max_know = 50, 25

    if include_safety and "safety" not in memory_types:
        memory_types = ["safety", *memory_types]

    if not classification["memory_needed"] and intent == "general_chat" and not force_safety:
        memory_types = []
        include_summary = False

    if not classification["knowledge_needed"]:
        include_knowledge = False

    return {
        "intent": intent,
        "memory_types": memory_types,
        "include_session_summary": include_summary,
        "include_machine_knowledge": include_knowledge,
        "include_safety": include_safety,
        "product": product,
        "product_version": product_version,
        "language": language,
        "max_memory_candidates": max_mem,
        "max_knowledge_candidates": max_know,
        "token_budget": token_budget,
        "strategy_version": HERMES_VERSION,
        "intent_confidence": classification["confidence"],
    }


def _parse_ts(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def _subject_key(text: str) -> str | None:
    norm = normalize_text(text)
    for subject, values in _CONFLICT_SUBJECTS:
        if subject in norm or any(v.strip() in norm for v in values):
            present = [v.strip() for v in values if v.strip() in norm]
            if present:
                return f"{subject}:{','.join(sorted(present)[:3])}"
            return subject
    return None


def detect_conflicts(
    memories: list[dict[str, Any]],
    knowledge: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Detect simple subject-level contradictions among candidates."""
    conflicts: list[dict[str, Any]] = []
    by_subject: dict[str, list[dict[str, Any]]] = {}

    for mem in memories:
        key = _subject_key(mem.get("content") or "")
        if not key:
            continue
        subject = key.split(":", 1)[0]
        by_subject.setdefault(subject, []).append({
            "id": mem.get("id"),
            "kind": "memory",
            "type": mem.get("type"),
            "content": mem.get("content"),
            "key": key,
            "confidence": float(mem.get("confidence", 0.5)),
            "updated_at": mem.get("updated_at") or mem.get("created_at"),
            "source": mem.get("source"),
            "trust": None,
            "product_version": mem.get("product_version"),
        })

    for chunk in knowledge:
        key = _subject_key(chunk.get("content") or "")
        if not key:
            continue
        subject = key.split(":", 1)[0]
        doc = chunk.get("_document") or {}
        by_subject.setdefault(subject, []).append({
            "id": chunk.get("id"),
            "kind": "knowledge",
            "type": "knowledge",
            "content": chunk.get("content"),
            "key": key,
            "confidence": 0.9,
            "updated_at": doc.get("updated_at"),
            "source": doc.get("source"),
            "trust": (chunk.get("_trust_level") or doc.get("trust_level") or "unknown"),
            "product_version": doc.get("version") or chunk.get("_doc_version"),
            "tier": chunk.get("_tier", 3),
        })

    for subject, items in by_subject.items():
        keys = {i["key"] for i in items}
        if len(keys) <= 1 or len(items) < 2:
            continue
        # Distinct value sets for same subject → conflict
        conflicts.append({
            "subject": subject,
            "candidate_ids": [i["id"] for i in items],
            "keys": sorted(keys),
            "items": items,
        })
    return conflicts


def _rank_conflict_item(item: dict[str, Any]) -> tuple:
    """Higher tuple wins. Priority: exact knowledge > safety > newer correction > confidence > trust > time."""
    is_exact_know = item["kind"] == "knowledge" and item.get("tier") == 1
    is_safety = item.get("type") == "safety"
    is_pref = item.get("type") in ("preference", "profile")
    trust_map = {"verified": 1.0, "manual": 0.95, "official": 0.95, "approved": 0.85, "qa": 0.7, "unknown": 0.4}
    trust = trust_map.get((item.get("trust") or "unknown").lower(), 0.4)
    if item["kind"] == "memory" and (item.get("source") or "") in ("manual", "verified"):
        trust = max(trust, 0.95)
    ts = _parse_ts(item.get("updated_at"))
    ts_score = ts.timestamp() if ts else 0.0
    return (
        1 if is_exact_know else 0,
        1 if is_safety else 0,
        1 if is_pref else 0,  # user correction preference over stale generic
        float(item.get("confidence") or 0),
        trust,
        ts_score,
    )


def resolve_conflicts(conflicts: list[dict[str, Any]]) -> dict[str, Any]:
    resolved: list[dict[str, Any]] = []
    suppressed: list[str] = []
    unresolved = 0
    reasons: list[dict[str, Any]] = []

    for conflict in conflicts:
        items = conflict["items"]
        ranked = sorted(items, key=_rank_conflict_item, reverse=True)
        winner = ranked[0]
        losers = ranked[1:]
        # Unresolved only when ranking signals are fully tied (including trust/time).
        if len(ranked) >= 2 and _rank_conflict_item(ranked[0]) == _rank_conflict_item(ranked[1]):
            unresolved += 1
            reasons.append({
                "subject": conflict["subject"],
                "reason": "unresolved_tie",
                "winner_id": None,
                "suppressed_ids": [],
            })
            continue
        reason = "exact_product_knowledge" if winner.get("tier") == 1 else (
            "safety_rule" if winner.get("type") == "safety" else (
                "newer_user_correction" if winner.get("type") in ("preference", "profile") else (
                    "higher_confidence" if float(winner.get("confidence") or 0) >= float(losers[0].get("confidence") or 0)
                    else "source_quality_or_recency"
                )
            )
        )
        suppressed.extend(i["id"] for i in losers if i.get("id"))
        resolved.append({"subject": conflict["subject"], "winner_id": winner["id"], "reason": reason})
        reasons.append({
            "subject": conflict["subject"],
            "reason": reason,
            "winner_id": winner["id"],
            "suppressed_ids": [i["id"] for i in losers if i.get("id")],
        })

    return {
        "resolved": resolved,
        "suppressed_ids": sorted(set(suppressed)),
        "unresolved_count": unresolved,
        "reasons": reasons,
        "conflicts_detected": len(conflicts),
        "conflicts_resolved": len(resolved),
    }


def compress_context(
    packet: dict[str, Any],
    *,
    budget: int,
    force_safety: bool,
) -> tuple[dict[str, Any], bool]:
    """Deterministic compression: dedupe overlap, shorten long prose, preserve safety meaning."""
    applied = False

    def shorten(text: str, max_tokens: int, *, preserve: bool = False) -> str:
        nonlocal applied
        if not text:
            return text
        est = estimate_tokens(text)
        if est <= max_tokens:
            return text
        if preserve:
            # Keep first and last sentence-ish fragments for safety
            parts = re.split(r"(?<=[.!?])\s+", text.strip())
            if len(parts) >= 2:
                kept = parts[0]
                if estimate_tokens(kept) < max_tokens:
                    applied = True
                    return kept
        max_chars = max(16, max_tokens * 4)
        applied = True
        out = text[:max_chars].rstrip()
        return out + ("…" if len(out) < len(text) else "")

    # Drop relevant memories that duplicate summary text
    summary = packet.get("recent_summary")
    if summary:
        sum_sig = normalize_text(summary)
        kept = []
        for mem in packet.get("relevant_memories") or []:
            if normalize_text(mem.get("content") or "") == sum_sig:
                applied = True
                continue
            if sum_sig and normalize_text(mem.get("content") or "") in sum_sig:
                applied = True
                continue
            kept.append(mem)
        packet["relevant_memories"] = kept

    # Shorten long non-safety items
    packet["relevant_memories"] = [
        {**m, "content": shorten(m.get("content") or "", 80)}
        for m in (packet.get("relevant_memories") or [])
    ]
    packet["user_profile"] = [
        {**m, "content": shorten(m.get("content") or "", 40)}
        for m in (packet.get("user_profile") or [])
    ]
    packet["knowledge_snippets"] = [
        {**k, "content": shorten(k.get("content") or "", 100)}
        for k in (packet.get("knowledge_snippets") or [])
    ]
    if packet.get("recent_summary"):
        packet["recent_summary"] = shorten(packet["recent_summary"], 60)

    # Safety: light trim only if over budget share; never empty
    safety = []
    for s in packet.get("safety_rules") or []:
        content = s.get("content") or ""
        if force_safety:
            content = shorten(content, 120, preserve=True)
        else:
            content = shorten(content, 100, preserve=True)
        if content:
            safety.append({**s, "content": content})
    packet["safety_rules"] = safety

    # Recompute used tokens
    used = 0
    for s in packet.get("safety_rules") or []:
        used += estimate_tokens(s.get("content"))
    if packet.get("recent_summary"):
        used += estimate_tokens(packet["recent_summary"])
    for k in packet.get("knowledge_snippets") or []:
        used += estimate_tokens(k.get("content"))
    for m in packet.get("relevant_memories") or []:
        used += estimate_tokens(m.get("content"))
    for m in packet.get("user_profile") or []:
        used += estimate_tokens(m.get("content"))
    packet["used_token_estimate"] = used
    if used > budget:
        # Drop lowest-priority profile first, then relevant
        while packet.get("user_profile") and packet["used_token_estimate"] > budget:
            dropped = packet["user_profile"].pop()
            packet["used_token_estimate"] -= estimate_tokens(dropped.get("content"))
            applied = True
        while packet.get("relevant_memories") and packet["used_token_estimate"] > budget:
            dropped = packet["relevant_memories"].pop()
            packet["used_token_estimate"] -= estimate_tokens(dropped.get("content"))
            applied = True
    return packet, applied


def build_context_packet(
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
    """Hermes orchestration: classify → plan → filter → Stage-3 pack → conflict → compress."""
    classification = classify_intent(query)
    plan = build_retrieval_plan(
        classification,
        product=product,
        product_version=product_version,
        language=language,
        token_budget=budget,
    )

    # Apply plan filters before Stage 3 packing
    allowed_types = set(plan["memory_types"])
    if plan["include_safety"]:
        allowed_types.add("safety")

    planned_memories = [
        m for m in memories
        if not allowed_types or m.get("type") in allowed_types
    ][: plan["max_memory_candidates"]]

    planned_knowledge = knowledge_chunks if plan["include_machine_knowledge"] else []
    planned_knowledge = planned_knowledge[: plan["max_knowledge_candidates"]]
    planned_summary = summary if plan["include_session_summary"] else None

    # Conflict detection on filtered approved candidates
    filtered_mem = filter_memory_candidates(
        planned_memories, language=language, product_version=product_version,
    )
    filtered_know = filter_knowledge_candidates(
        planned_knowledge, product=product, locale=language, product_version=product_version,
    ) if plan["include_machine_knowledge"] else []

    conflicts = detect_conflicts(filtered_mem, filtered_know)
    resolution = resolve_conflicts(conflicts)
    suppressed = set(resolution["suppressed_ids"])

    filtered_mem = [m for m in filtered_mem if m.get("id") not in suppressed]
    filtered_know = [c for c in filtered_know if c.get("id") not in suppressed]

    packet, usage = retrieve_and_pack_context(
        request_id=request_id,
        language=language,
        budget=budget,
        query=query,
        product=product if plan["include_machine_knowledge"] else "generic",
        product_version=product_version,
        summary=planned_summary,
        memories=filtered_mem,
        knowledge_chunks=filtered_know,
    )

    packet, compression_applied = compress_context(
        packet, budget=budget, force_safety=plan["include_safety"],
    )

    # Recompute selected_count after compression
    selected_count = (
        len(packet.get("safety_rules") or [])
        + len(packet.get("knowledge_snippets") or [])
        + len(packet.get("relevant_memories") or [])
        + len(packet.get("user_profile") or [])
        + (1 if packet.get("recent_summary") else 0)
    )
    packet["selected_count"] = selected_count
    packet["memory_version"] = MEMORY_PACKET_VERSION_HERMES
    packet["intent"] = classification["intent"]
    packet["hermes_version"] = HERMES_VERSION
    packet["intent_confidence"] = classification["confidence"]
    packet["retrieval_plan"] = {
        "intent": plan["intent"],
        "memory_types": plan["memory_types"],
        "include_session_summary": plan["include_session_summary"],
        "include_machine_knowledge": plan["include_machine_knowledge"],
        "include_safety": plan["include_safety"],
        "strategy_version": plan["strategy_version"],
    }
    packet["conflicts_detected"] = resolution["conflicts_detected"]
    packet["conflicts_resolved"] = resolution["conflicts_resolved"]
    packet["unresolved_conflicts"] = resolution["unresolved_count"]
    packet["compression_applied"] = compression_applied
    if resolution["unresolved_count"] > 0:
        # Compact clarification hint — not raw DB detail
        packet["clarification_hint"] = "conflicting_memories_need_clarification"

    usage = {
        **usage,
        "hermes_version": HERMES_VERSION,
        "intent": classification["intent"],
        "intent_confidence": classification["confidence"],
        "matched_signals": classification["matched_signals"],
        "retrieval_plan": packet["retrieval_plan"],
        "conflicts_detected": resolution["conflicts_detected"],
        "conflicts_resolved": resolution["conflicts_resolved"],
        "unresolved_conflicts": resolution["unresolved_count"],
        "resolution_reasons": [
            {"subject": r["subject"], "reason": r["reason"], "winner_id": r.get("winner_id")}
            for r in resolution["reasons"]
        ],
        "suppressed_ids": resolution["suppressed_ids"],
        "compression_applied": compression_applied,
        "selected_count": selected_count,
        "used_token_estimate": packet["used_token_estimate"],
        "query_len": len(query or ""),
        "permanent_memory_writes": 0,
        "backend_llm_invoked": False,
    }
    return packet, usage


def hermes_empty_fields() -> dict[str, Any]:
    return {
        "hermes_version": HERMES_VERSION,
        "intent": "unknown",
        "intent_confidence": 0.0,
        "retrieval_plan": {
            "intent": "unknown",
            "memory_types": [],
            "include_session_summary": False,
            "include_machine_knowledge": False,
            "include_safety": False,
            "strategy_version": HERMES_VERSION,
        },
        "conflicts_detected": 0,
        "conflicts_resolved": 0,
        "unresolved_conflicts": 0,
        "compression_applied": False,
        "memory_version": MEMORY_PACKET_VERSION_HERMES,
    }
