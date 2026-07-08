package com.personaledge.ai.voice

/**
 * Pure, Android-free decision helpers for the voice lifecycle so the success/stale logic can be
 * unit tested without a device. The [VoiceModeScreen] uses these to decide whether a completed
 * voice turn should be spoken or should return straight to listening, and to reject stale TTS
 * completion callbacks after Stop AI / a superseding turn.
 */
object VoiceLogic {

    enum class SuccessAction { SPEAK, LISTEN }

    /**
     * On a successful non-blank voice reply: SPEAK only when read-aloud is on, TTS is ready, and
     * there is a speakable chunk; otherwise LISTEN (the answer stays visible in chat and the UI
     * never stays stuck on Thinking).
     */
    fun successAction(
        autoReadReplies: Boolean,
        ttsReady: Boolean,
        hasSpeakableChunk: Boolean,
    ): SuccessAction =
        if (autoReadReplies && ttsReady && hasSpeakableChunk) SuccessAction.SPEAK else SuccessAction.LISTEN

    /**
     * A TTS completion callback is stale when it carries no id (0 = invalidated by stop) or an id
     * that no longer matches the active speech (a newer speech started, or speech was invalidated).
     */
    fun isStaleSpeechCallback(callbackSpeechId: Long, activeSpeechId: Long): Boolean =
        callbackSpeechId == 0L || callbackSpeechId != activeSpeechId

    /**
     * A durable terminal voice outcome (ReadyToSpeak / Error) may be acted on exactly once, and only
     * when the screen was actually processing the matching turn. This is the fix for the Listening
     * loop / re-entry replay: a terminal that is already [consumed] (durably, in the ViewModel), that
     * arrives with no active session, or that the screen was NOT mid-turn for (e.g. a stale terminal
     * or one completed while the screen was gone) must NOT reopen the microphone or restart TTS.
     */
    fun shouldConsumeTerminal(consumed: Boolean, sessionActive: Boolean, screenWasProcessing: Boolean): Boolean =
        !consumed && sessionActive && screenWasProcessing

    /**
     * A live STT callback (partial/final) belongs to the currently-active utterance. An id of 0 means
     * the utterance was invalidated (Stop AI, screen exit, generation start, explicit STT stop), so
     * late callbacks from a cancelled recognizer are rejected.
     */
    fun isActiveUtterance(callbackUtteranceId: Long, activeUtteranceId: Long): Boolean =
        callbackUtteranceId != 0L && callbackUtteranceId == activeUtteranceId

    /**
     * One STT utterance id may submit exactly once. Identity-based (not text-based): an invalidated
     * id (0) never submits, and re-submitting the same id is rejected — but a user intentionally
     * repeating the same words produces a NEW id and is therefore allowed.
     */
    fun canSubmitUtterance(utteranceId: Long, lastSubmittedUtteranceId: Long): Boolean =
        utteranceId != 0L && utteranceId != lastSubmittedUtteranceId

    /**
     * STT may (re)open for listening only when no generation or speech is active. Prevents a stray
     * effect/recomposition from reopening the mic while the turn is still Generating/Thinking/Speaking.
     */
    fun canAcceptListening(isLoading: Boolean, generating: Boolean, thinking: Boolean, speaking: Boolean): Boolean =
        !isLoading && !generating && !thinking && !speaking
}
