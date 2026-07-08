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
     * A durable terminal voice outcome (ReadyToSpeak / Error) should be acted on only when its
     * [sequence] is newer than the last one already handled. This makes reconciliation from the
     * StateFlow idempotent: a recomposition or re-subscription that re-reads the same completed
     * state cannot start TTS (or re-show a notice) twice for the same turn.
     */
    fun shouldHandleTerminal(sequence: Long, lastHandledSequence: Long): Boolean =
        sequence > lastHandledSequence
}
