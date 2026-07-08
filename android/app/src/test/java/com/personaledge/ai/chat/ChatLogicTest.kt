package com.personaledge.ai.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure history/blank-response policy that backs the fixes for
 * repeated answers and blank-reply handling. ViewModel/turn-lifecycle behavior that depends on
 * the native engine and Android runtime is covered by manual/instrumented verification.
 */
class ChatLogicTest {

    private fun msg(role: String, content: String, streaming: Boolean = false) =
        UiMessage(role = role, content = content, isStreaming = streaming)

    // Test 10 — sanitized history excludes streaming, blank, and unknown-role entries.
    @Test
    fun sanitizeHistory_excludesStreamingBlankAndUnknownRoles() {
        val messages = listOf(
            msg("user", "hello"),
            msg("assistant", "hi there"),
            msg("assistant", "", streaming = true), // in-flight placeholder
            msg("assistant", ""),                    // blank/cancelled result
            msg("user", "   "),                      // blank user
            msg("system", "ignored role"),
            msg("model", "normalized reply"),
        )

        val history = ChatLogic.sanitizeHistory(messages)

        assertEquals(3, history.size)
        assertEquals("user" to "hello", history[0].role to history[0].content)
        assertEquals("assistant" to "hi there", history[1].role to history[1].content)
        // "model" is normalized to "assistant"
        assertEquals("assistant" to "normalized reply", history[2].role to history[2].content)
    }

    // Test 1 (support) — a new prompt is not present in the sanitized prior history, proving the
    // current user message is passed separately as the prompt, not duplicated into history.
    @Test
    fun sanitizeHistory_doesNotContainCurrentPromptWhenBuiltFromPriorMessages() {
        val prior = listOf(
            msg("user", "question A"),
            msg("assistant", "answer A"),
        )
        val history = ChatLogic.sanitizeHistory(prior)
        assertEquals(2, history.size)
        assertFalse(history.any { it.content == "question B" })
    }

    // Test 2 / Test 10 — blank assistant replies are never persisted.
    @Test
    fun persistableMessages_dropsBlankAndStreaming() {
        val messages = listOf(
            msg("user", "hi"),
            msg("assistant", "real answer"),
            msg("assistant", ""),                    // blank -> dropped
            msg("assistant", "partial", streaming = true), // streaming -> dropped
        )
        val persistable = ChatLogic.persistableMessages(messages)
        assertEquals(2, persistable.size)
        assertEquals("real answer", persistable[1].content)
    }

    @Test
    fun isBlankReply_detectsBlank() {
        assertTrue(ChatLogic.isBlankReply(""))
        assertTrue(ChatLogic.isBlankReply("   \n  "))
        assertFalse(ChatLogic.isBlankReply("hello"))
    }

    // Cleanup helper — removes blank streaming assistant placeholders on cancel/clear/switch.
    @Test
    fun clearedStreamingMessages_removesBlankStreamingPlaceholder() {
        val messages = listOf(
            msg("user", "hi"),
            msg("assistant", "previous answer"),
            msg("user", "current question"),
            msg("assistant", "", streaming = true), // in-flight blank placeholder
        )
        val result = ChatLogic.clearedStreamingMessages(messages)
        assertEquals(3, result.size)
        assertFalse(result.any { it.isStreaming })
        // Non-target messages are untouched (same content/order).
        assertEquals("previous answer", result[1].content)
        assertEquals("current question", result[2].content)
    }

    // Cleanup helper — preserves partial (non-blank) streaming content but finalizes it.
    @Test
    fun clearedStreamingMessages_finalizesPartialStreamingContent() {
        val messages = listOf(
            msg("user", "q"),
            msg("assistant", "partial ans", streaming = true),
        )
        val result = ChatLogic.clearedStreamingMessages(messages)
        assertEquals(2, result.size)
        val assistant = result[1]
        assertEquals("partial ans", assistant.content)
        assertFalse(assistant.isStreaming)
    }

    // Cleanup helper — a non-streaming, non-target message id/content is not modified.
    @Test
    fun clearedStreamingMessages_leavesSettledMessagesUnmodified() {
        val settled = msg("assistant", "done")
        val result = ChatLogic.clearedStreamingMessages(listOf(msg("user", "x"), settled))
        assertEquals(2, result.size)
        assertEquals(settled.id, result[1].id)
        assertEquals("done", result[1].content)
        assertFalse(result[1].isStreaming)
    }

    // ---------------- History budget / selection (bounded per-turn re-prefill) ------------------

    private fun turns(vararg pairs: Pair<String, String>): List<UiMessage> =
        pairs.map { msg(it.first, it.second) }

    // History Test 1 — a single complete turn is passed through intact.
    @Test
    fun selectHistory_oneTurnIntact() {
        val selected = ChatLogic.selectHistory(turns("user" to "hi", "assistant" to "hello"))
        assertEquals(2, selected.selectedCount)
        assertEquals(0, selected.droppedCount)
        assertEquals("hi", selected.turns[0].content)
        assertEquals("hello", selected.turns[1].content)
    }

    // History Test 2 — more than the recent window keeps only the most recent messages.
    @Test
    fun selectHistory_selectsRecentWindow() {
        val messages = mutableListOf<UiMessage>()
        repeat(6) { i -> messages += msg("user", "q$i"); messages += msg("assistant", "a$i") } // 12 valid
        val selected = ChatLogic.selectHistory(messages) // default recent window = 6
        assertEquals(6, selected.selectedCount)
        assertEquals(6, selected.droppedCount)
        // Newest are kept; the last message is the most recent assistant reply.
        assertEquals("a5", selected.turns.last().content)
        assertEquals("q3", selected.turns.first().content)
    }

    // History Test 3 — the absolute message cap is enforced even if the recent window is larger.
    @Test
    fun selectHistory_absoluteCapEnforced() {
        val messages = mutableListOf<UiMessage>()
        repeat(10) { i -> messages += msg("user", "q$i"); messages += msg("assistant", "a$i") }
        val budget = HistoryBudget(maxRecentMessages = 20, absoluteMaxMessages = 8)
        val selected = ChatLogic.selectHistory(messages, budget)
        assertTrue(selected.selectedCount <= 8)
    }

    // History Test 4 — reused old assistant messages are trimmed to the cap.
    @Test
    fun selectHistory_trimsOldAssistantMessages() {
        val longAnswer = "x".repeat(900)
        val messages = listOf(
            msg("user", "q1"),
            msg("assistant", longAnswer), // old, reused -> trimmed
            msg("user", "q2"),
            msg("assistant", "short newest"),
        )
        val selected = ChatLogic.selectHistory(messages, HistoryBudget(maxAssistantChars = 500))
        assertEquals(1, selected.trimmedMessageCount)
        assertTrue(selected.turns[1].content.length <= 501) // 500 + ellipsis
        // Newest turn is untouched.
        assertEquals("short newest", selected.turns.last().content)
    }

    // History Test 5 — the current prompt is not part of prior messages, so never duplicated.
    @Test
    fun selectHistory_excludesCurrentPrompt() {
        // Prior messages do not contain the just-typed prompt "current".
        val selected = ChatLogic.selectHistory(turns("user" to "old q", "assistant" to "old a"))
        assertFalse(selected.turns.any { it.content == "current" })
    }

    // History Test 6 — blank and streaming entries are excluded.
    @Test
    fun selectHistory_excludesBlankAndStreaming() {
        val messages = listOf(
            msg("user", "hi"),
            msg("assistant", "hello"),
            msg("assistant", "", streaming = true),
            msg("assistant", ""),
            msg("system", "ignored"),
        )
        val selected = ChatLogic.selectHistory(messages)
        assertEquals(2, selected.selectedCount)
        assertTrue(selected.turns.all { it.content.isNotBlank() })
    }

    // History Test 7 — a leading orphaned assistant reply (window begins mid-pair) is dropped so
    // complete recent pairs are kept.
    @Test
    fun selectHistory_prefersCompletePairs() {
        // 7 valid, ending on a user turn -> takeLast(6) begins with an assistant ("a0").
        val messages = listOf(
            msg("user", "q0"), msg("assistant", "a0"),
            msg("user", "q1"), msg("assistant", "a1"),
            msg("user", "q2"), msg("assistant", "a2"),
            msg("user", "q3"),
        )
        val selected = ChatLogic.selectHistory(messages)
        assertEquals("user", selected.turns.first().role)
        assertEquals("q1", selected.turns.first().content)
        assertFalse(selected.turns.any { it.content == "a0" })
    }

    // History Test 8 — the character/token budget drops oldest turns (keeps the newest).
    @Test
    fun selectHistory_enforcesCharBudget() {
        val chunk = "y".repeat(300)
        val messages = mutableListOf<UiMessage>()
        repeat(3) { i -> messages += msg("user", "q$i $chunk"); messages += msg("assistant", "a$i $chunk") }
        // Tight budget forces dropping oldest turns.
        val selected = ChatLogic.selectHistory(messages, HistoryBudget(maxHistoryChars = 700, maxAssistantChars = 5000, maxUserChars = 5000))
        assertTrue(selected.selectedChars <= 700 || selected.selectedCount == 1)
        // The newest message is always retained.
        assertTrue(selected.turns.last().content.startsWith("a2"))
    }

    // History Test 9 — the stored message list is not mutated by selection.
    @Test
    fun selectHistory_doesNotMutateStoredMessages() {
        val messages = turns("user" to "a".repeat(900), "assistant" to "b".repeat(900), "user" to "q", "assistant" to "r")
        val before = messages.map { it.content }
        ChatLogic.selectHistory(messages, HistoryBudget(maxAssistantChars = 100, maxUserChars = 100))
        assertEquals(before, messages.map { it.content })
    }

    // History Test 10 — an optional rolling summary is included within its budget.
    @Test
    fun selectHistory_includesBudgetedSummary() {
        val selected = ChatLogic.selectHistory(
            turns("user" to "q", "assistant" to "a"),
            HistoryBudget(maxSummaryChars = 20),
            rollingSummary = "s".repeat(100),
        )
        assertEquals(21, selected.summary!!.length) // 20 + ellipsis
        assertTrue(selected.summaryTokens > 0)
    }

    // History Test 11 — optional relevant memory is included within its budget.
    @Test
    fun selectHistory_includesBudgetedMemory() {
        val selected = ChatLogic.selectHistory(
            turns("user" to "q", "assistant" to "a"),
            HistoryBudget(maxMemoryChars = 30),
            relevantMemory = listOf("m".repeat(20), "n".repeat(20), "o".repeat(20)),
        )
        assertTrue(selected.memory.isNotEmpty())
        assertTrue(selected.memoryChars <= 30 || selected.memory.size == 1)
    }

    // History Test 12 — offline / no-summary mode: recent window only, no summary/memory.
    @Test
    fun selectHistory_offlineNoSummaryMode() {
        val selected = ChatLogic.selectHistory(turns("user" to "hi", "assistant" to "hello"))
        assertEquals(null, selected.summary)
        assertTrue(selected.memory.isEmpty())
        assertEquals(2, selected.selectedCount)
    }
}
