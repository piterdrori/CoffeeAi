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

    // ---------------- Recovery-fix guards (terminal consumption + utterance identity) ----------

    // Test 1/4 — a terminal is handled once, while the screen was mid-turn in an active session.
    @Test
    fun shouldConsumeTerminal_handledOnceWhileProcessing() {
        assertTrue(
            VoiceLogic.shouldConsumeTerminal(consumed = false, sessionActive = true, screenWasProcessing = true),
        )
        // Already consumed -> not handled again (no repeated resume / no double TTS).
        assertFalse(
            VoiceLogic.shouldConsumeTerminal(consumed = true, sessionActive = true, screenWasProcessing = true),
        )
    }

    // Test 2 — a terminal replayed after screen re-entry (not mid-turn) is rejected.
    @Test
    fun shouldConsumeTerminal_rejectedOnReEntry() {
        assertFalse(
            VoiceLogic.shouldConsumeTerminal(consumed = false, sessionActive = true, screenWasProcessing = false),
        )
    }

    // Test 3 — a terminal with no active session does not resume listening.
    @Test
    fun shouldConsumeTerminal_rejectedWhenSessionInactive() {
        assertFalse(
            VoiceLogic.shouldConsumeTerminal(consumed = false, sessionActive = false, screenWasProcessing = true),
        )
    }

    // Test 6/7 — only the active utterance id may emit live text; 0 (invalidated) is rejected.
    @Test
    fun isActiveUtterance_onlyMatchingNonZero() {
        assertTrue(VoiceLogic.isActiveUtterance(callbackUtteranceId = 7L, activeUtteranceId = 7L))
        assertFalse(VoiceLogic.isActiveUtterance(callbackUtteranceId = 6L, activeUtteranceId = 7L))
        // After Stop AI / exit / generation start invalidates (active = 0), any callback is stale.
        assertFalse(VoiceLogic.isActiveUtterance(callbackUtteranceId = 7L, activeUtteranceId = 0L))
        assertFalse(VoiceLogic.isActiveUtterance(callbackUtteranceId = 0L, activeUtteranceId = 0L))
    }

    // Test 5/8/9/11/12 — one utterance id submits exactly once; new ids (incl. repeated words) allowed.
    @Test
    fun canSubmitUtterance_identityBased() {
        // First submit for a fresh id.
        assertTrue(VoiceLogic.canSubmitUtterance(utteranceId = 3L, lastSubmittedUtteranceId = 0L))
        // Same id cannot submit twice.
        assertFalse(VoiceLogic.canSubmitUtterance(utteranceId = 3L, lastSubmittedUtteranceId = 3L))
        // Invalidated id (0) — e.g. after Stop AI / exit — never submits.
        assertFalse(VoiceLogic.canSubmitUtterance(utteranceId = 0L, lastSubmittedUtteranceId = 3L))
        // A new utterance after a completed turn is accepted (Test 8) — even if the user repeats the
        // exact same words, because it is a new id (Test 9).
        assertTrue(VoiceLogic.canSubmitUtterance(utteranceId = 4L, lastSubmittedUtteranceId = 3L))
    }

    // Test 10 — generation/speech state prevents opening STT for a new listen.
    @Test
    fun canAcceptListening_blockedWhileBusy() {
        assertTrue(VoiceLogic.canAcceptListening(isLoading = false, generating = false, thinking = false, speaking = false))
        assertFalse(VoiceLogic.canAcceptListening(isLoading = true, generating = false, thinking = false, speaking = false))
        assertFalse(VoiceLogic.canAcceptListening(isLoading = false, generating = true, thinking = false, speaking = false))
        assertFalse(VoiceLogic.canAcceptListening(isLoading = false, generating = false, thinking = true, speaking = false))
        assertFalse(VoiceLogic.canAcceptListening(isLoading = false, generating = false, thinking = false, speaking = true))
    }
}
