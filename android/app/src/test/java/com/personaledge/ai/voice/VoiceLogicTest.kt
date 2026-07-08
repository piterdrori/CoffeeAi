package com.personaledge.ai.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure voice success/stale decision logic backing the fixes for:
 *  - stuck-on-Thinking (explicit SPEAK/LISTEN outcome, never "no decision"),
 *  - stale TTS callbacks after Stop AI / superseding turn.
 * UI phase/flow that needs the ViewModel + TTS engine is covered by manual device tests.
 */
class VoiceLogicTest {

    // Test 1 — voice success + read-aloud on + TTS ready + speakable chunk => SPEAK (Thinking→Speaking).
    @Test
    fun successAction_speaksWhenEnabledReadyAndHasChunk() {
        assertEquals(VoiceLogic.SuccessAction.SPEAK, VoiceLogic.successAction(true, true, true))
    }

    // Test 2 — read-aloud off => LISTEN (Thinking→Listening, no TTS request), never stuck.
    @Test
    fun successAction_listensWhenReadAloudOff() {
        assertEquals(VoiceLogic.SuccessAction.LISTEN, VoiceLogic.successAction(false, true, true))
    }

    // Test 7 — TTS unavailable => LISTEN (return to listening, not stuck on Thinking/Speaking).
    @Test
    fun successAction_listensWhenTtsNotReady() {
        assertEquals(VoiceLogic.SuccessAction.LISTEN, VoiceLogic.successAction(true, false, true))
    }

    @Test
    fun successAction_listensWhenNoSpeakableChunk() {
        assertEquals(VoiceLogic.SuccessAction.LISTEN, VoiceLogic.successAction(true, true, false))
    }

    // Test 4 — after Stop invalidates the speech id (active=0), any callback is stale.
    @Test
    fun staleCallback_afterStopInvalidation() {
        assertTrue(VoiceLogic.isStaleSpeechCallback(callbackSpeechId = 5L, activeSpeechId = 0L))
        assertTrue(VoiceLogic.isStaleSpeechCallback(callbackSpeechId = 0L, activeSpeechId = 0L))
    }

    // Test 5 — an old speech callback after a new speech started cannot be treated as current.
    @Test
    fun staleCallback_afterNewSpeechStarted() {
        assertTrue(VoiceLogic.isStaleSpeechCallback(callbackSpeechId = 5L, activeSpeechId = 6L))
    }

    // Test 3 — the current speech's callback is accepted.
    @Test
    fun currentCallback_isNotStale() {
        assertFalse(VoiceLogic.isStaleSpeechCallback(callbackSpeechId = 6L, activeSpeechId = 6L))
    }

    // Test 21 — the same ReadyToSpeak sequence cannot start TTS twice (idempotent reconciliation).
    @Test
    fun shouldHandleTerminal_onlyNewerSequences() {
        assertTrue(VoiceLogic.shouldHandleTerminal(sequence = 1L, lastHandledSequence = 0L))
        // Re-reading the same completed state (recomposition / re-subscription) must NOT re-fire.
        assertFalse(VoiceLogic.shouldHandleTerminal(sequence = 1L, lastHandledSequence = 1L))
        // A stale/older sequence is also ignored.
        assertFalse(VoiceLogic.shouldHandleTerminal(sequence = 1L, lastHandledSequence = 2L))
        // The next turn's outcome is handled.
        assertTrue(VoiceLogic.shouldHandleTerminal(sequence = 3L, lastHandledSequence = 2L))
    }
}
