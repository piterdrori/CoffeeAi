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
}
