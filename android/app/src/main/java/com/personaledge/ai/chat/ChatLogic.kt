package com.personaledge.ai.chat

import com.personaledge.ai.inference.ChatTurn
import com.personaledge.ai.perf.PerfCalc

/**
 * Tunable limits for how much prior conversation is re-fed to the local model each turn.
 *
 * Because a fresh native conversation is built for every turn, the whole selected history is
 * re-prefilled every turn — so an unbounded history makes first-token latency grow without limit.
 * This budget keeps the re-prefilled context bounded (and therefore latency roughly stable) while
 * preserving the most recent, most relevant turns. The character caps are derived from token
 * targets using a conservative ~4 chars/token estimate (see [PerfCalc.CHARS_PER_TOKEN_ESTIMATE]);
 * no tokenizer dependency is introduced.
 *
 * Token targets (approximate): system ~150, backend memory up to ~300, rolling summary up to ~200,
 * recent conversation up to ~800, current prompt reserves the remainder → total input 1,500–2,000.
 */
data class HistoryBudget(
    /** Retain the last N valid messages in full (≈3 complete user/assistant turns). */
    val maxRecentMessages: Int = 6,
    /** Absolute hard cap on selected messages, regardless of anything else. */
    val absoluteMaxMessages: Int = 8,
    /** Reused (non-newest) assistant messages are trimmed to at most this many chars. */
    val maxAssistantChars: Int = 500,
    /** Reused user messages are trimmed only when unusually long. */
    val maxUserChars: Int = 1200,
    /** Total character budget for the selected recent conversation (~800 tokens). */
    val maxHistoryChars: Int = 3200,
    /** Character budget for the optional rolling summary (~200 tokens). */
    val maxSummaryChars: Int = 800,
    /** Total character budget for optional relevant backend memory (~300 tokens). */
    val maxMemoryChars: Int = 1200,
)

/**
 * Result of [ChatLogic.selectHistory]. [turns] is what gets re-prefilled; the remaining fields are
 * privacy-safe diagnostics (counts and lengths only — never raw content) plus the budgeted optional
 * Hermes [summary] / [memory] that a future online path can fold into the system instruction.
 */
data class SelectedHistory(
    val turns: List<ChatTurn>,
    val summary: String?,
    val memory: List<String>,
    val totalStoredMessages: Int,
    val validMessages: Int,
    val selectedCount: Int,
    val droppedCount: Int,
    val trimmedMessageCount: Int,
    val selectedChars: Int,
    val historyTokens: Int,
    val summaryChars: Int,
    val summaryTokens: Int,
    val memoryChars: Int,
    val memoryTokens: Int,
)

/**
 * Pure, Android-free helpers for the chat/voice generation lifecycle so they can be unit tested
 * without a device. Keeping this logic here (instead of inline in [ChatViewModel]) lets us verify
 * history sanitization and reply-blank detection deterministically.
 */
object ChatLogic {

    private val ROLES = setOf("user", "assistant", "model")

    /**
     * Builds the conversation history passed to the model. Excludes anything that must never be
     * fed back into inference or persisted:
     *  - streaming (in-flight) messages,
     *  - blank messages (blank assistant placeholders / failed or cancelled turns),
     *  - unknown roles.
     * "model" is normalized to "assistant".
     */
    fun sanitizeHistory(messages: List<UiMessage>): List<ChatTurn> =
        messages
            .filter { !it.isStreaming && it.content.isNotBlank() && it.role in ROLES }
            .map { ChatTurn(if (it.role == "model") "assistant" else it.role, it.content) }

    /**
     * Selects a strictly bounded slice of prior conversation to re-prefill for the next turn, so
     * first-token latency stays roughly stable no matter how long the visible transcript grows.
     * This never mutates [messages] or the stored transcript — trimming applies only to the copy
     * fed to inference. Text and voice use the same policy.
     *
     * Policy (see [HistoryBudget]):
     *  - start from sanitized valid history (no streaming / blank / unknown-role; model→assistant);
     *  - keep the most recent window (bounded by both [HistoryBudget.maxRecentMessages] and the
     *    absolute [HistoryBudget.absoluteMaxMessages]) — newest turns are always preferred;
     *  - drop a leading orphaned assistant reply so complete recent pairs are kept where possible;
     *  - trim reused (non-newest) old messages to their per-role caps; the newest turn is kept full;
     *  - then drop the oldest turns until the character/token budget is satisfied (never truncate
     *    the newest turn to fit).
     *
     * The current user prompt is NOT part of [messages] (it is sent separately), so it can never be
     * duplicated into history. Optional [rollingSummary] / [relevantMemory] are the Hermes-ready
     * contract: budgeted here but returned separately (offline callers simply pass nothing).
     */
    fun selectHistory(
        messages: List<UiMessage>,
        budget: HistoryBudget = HistoryBudget(),
        rollingSummary: String? = null,
        relevantMemory: List<String> = emptyList(),
    ): SelectedHistory {
        val valid = messages
            .filter { !it.isStreaming && it.content.isNotBlank() && it.role in ROLES }
            .map { ChatTurn(if (it.role == "model") "assistant" else it.role, it.content) }

        val windowSize = minOf(budget.maxRecentMessages, budget.absoluteMaxMessages).coerceAtLeast(0)
        var window = valid.takeLast(windowSize)
        // Prefer complete pairs: a leading assistant reply lost its user turn to the window edge.
        if (window.size > 1 && window.first().role == "assistant") {
            window = window.drop(1)
        }

        var trimmedCount = 0
        val lastIndex = window.lastIndex
        val trimmed = window.mapIndexed { index, turn ->
            if (index == lastIndex) return@mapIndexed turn // newest turn kept in full
            val cap = if (turn.role == "user") budget.maxUserChars else budget.maxAssistantChars
            if (turn.content.length > cap) {
                trimmedCount++
                turn.copy(content = turn.content.take(cap).trimEnd() + "…")
            } else {
                turn
            }
        }

        // Enforce the char/token budget by dropping the oldest turns; keep the newest intact.
        val budgeted = ArrayDeque<ChatTurn>()
        var runningChars = 0
        for (turn in trimmed.asReversed()) {
            val len = turn.content.length
            if (budgeted.isNotEmpty() && runningChars + len > budget.maxHistoryChars) break
            budgeted.addFirst(turn)
            runningChars += len
        }
        val selected = budgeted.toList()

        val summary = rollingSummary?.trim()?.takeIf { it.isNotBlank() }?.let {
            if (it.length > budget.maxSummaryChars) it.take(budget.maxSummaryChars).trimEnd() + "…" else it
        }
        val memory = capMemory(relevantMemory, budget)

        val selectedChars = selected.sumOf { it.content.length }
        val summaryChars = summary?.length ?: 0
        val memoryChars = memory.sumOf { it.length }
        return SelectedHistory(
            turns = selected,
            summary = summary,
            memory = memory,
            totalStoredMessages = messages.size,
            validMessages = valid.size,
            selectedCount = selected.size,
            droppedCount = valid.size - selected.size,
            trimmedMessageCount = trimmedCount,
            selectedChars = selectedChars,
            historyTokens = PerfCalc.estimateTokens(selectedChars),
            summaryChars = summaryChars,
            summaryTokens = PerfCalc.estimateTokens(summaryChars),
            memoryChars = memoryChars,
            memoryTokens = PerfCalc.estimateTokens(memoryChars),
        )
    }

    /** Budgets optional relevant-memory items to a total character cap (Hermes-ready contract). */
    private fun capMemory(memory: List<String>, budget: HistoryBudget): List<String> {
        if (memory.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var used = 0
        for (raw in memory) {
            val item = raw.trim()
            if (item.isBlank()) continue
            if (out.isNotEmpty() && used + item.length > budget.maxMemoryChars) break
            val clipped = if (item.length > budget.maxMemoryChars) item.take(budget.maxMemoryChars) else item
            out.add(clipped)
            used += clipped.length
        }
        return out
    }

    /** Messages that are safe to persist — never persist blank or still-streaming messages. */
    fun persistableMessages(messages: List<UiMessage>): List<UiMessage> =
        messages.filter { !it.isStreaming && it.content.isNotBlank() }

    /** A reply is "blank" (nothing usable) if, after cleanup, no visible characters remain. */
    fun isBlankReply(cleaned: String): Boolean = cleaned.isBlank()

    /**
     * Cleans up in-flight streaming messages when a generation is stopped (cancel, clear-chat,
     * session switch, machine-command cancel):
     *  - removes blank streaming assistant placeholders (nothing was produced),
     *  - finalizes any remaining streaming message (isStreaming = false) so partial, real content
     *    is preserved rather than left mid-stream.
     * Non-streaming messages are untouched.
     */
    fun clearedStreamingMessages(messages: List<UiMessage>): List<UiMessage> =
        messages.mapNotNull { m ->
            when {
                m.role == "assistant" && m.isStreaming && m.content.isBlank() -> null
                m.isStreaming -> m.copy(isStreaming = false)
                else -> m
            }
        }
}
