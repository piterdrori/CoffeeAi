package com.personaledge.ai.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure Let's Chat per-message (manual) TTS rules: which replies get a button,
 * which reply is playing, tap start/stop, and stale-callback rejection.
 */
class ChatTtsLogicTest {

    // Test 2/3/4 — only finalized, non-empty assistant replies get a button.
    @Test
    fun showButton_onlyFinalizedAssistantReplies() {
        assertTrue(ChatTtsLogic.showButton("assistant", "Here you go", isStreaming = false))
        // User messages do not.
        assertFalse(ChatTtsLogic.showButton("user", "hello", isStreaming = false))
        // Streaming assistant messages do not.
        assertFalse(ChatTtsLogic.showButton("assistant", "partial", isStreaming = true))
        // Blank assistant messages do not.
        assertFalse(ChatTtsLogic.showButton("assistant", "   ", isStreaming = false))
    }

    // Test 5 — only the active reply shows as playing.
    @Test
    fun isPlaying_matchesActiveMessageOnly() {
        assertTrue(ChatTtsLogic.isPlaying("A", activeTtsMessageId = "A"))
        assertFalse(ChatTtsLogic.isPlaying("B", activeTtsMessageId = "A"))
        assertFalse(ChatTtsLogic.isPlaying("A", activeTtsMessageId = null))
    }

    // Test 5/6/7 — tap active = STOP, tap another (or when idle) = START.
    @Test
    fun resolveTap_startStopSemantics() {
        // Nothing playing -> tapping A starts A.
        assertEquals(ChatTtsLogic.Tap.START, ChatTtsLogic.resolveTap("A", activeTtsMessageId = null))
        // A playing, tap B -> start B (A implicitly stops in the caller).
        assertEquals(ChatTtsLogic.Tap.START, ChatTtsLogic.resolveTap("B", activeTtsMessageId = "A"))
        // A playing, tap A again -> stop.
        assertEquals(ChatTtsLogic.Tap.STOP, ChatTtsLogic.resolveTap("A", activeTtsMessageId = "A"))
    }

    // Test 8/9 — completion for the active speech clears; stale callbacks (switched/exited) rejected.
    @Test
    fun isStaleCallback_rejectsOldAndInvalidated() {
        // Active speech's own completion is NOT stale.
        assertFalse(ChatTtsLogic.isStaleCallback(callbackSpeechId = 4L, activeSpeechId = 4L))
        // A stale completion from reply A after switching to B (active=5) is rejected.
        assertTrue(ChatTtsLogic.isStaleCallback(callbackSpeechId = 4L, activeSpeechId = 5L))
        // Invalidated (stop / screen exit sets active=0) rejects any callback.
        assertTrue(ChatTtsLogic.isStaleCallback(callbackSpeechId = 4L, activeSpeechId = 0L))
    }
}
