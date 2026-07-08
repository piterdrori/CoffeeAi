package com.personaledge.ai.chat

/**
 * Pure, Android-free rules for the Let's Chat per-message (manual) TTS so the button visibility and
 * play/stop/stale decisions can be unit tested without Compose or the TTS engine.
 */
object ChatTtsLogic {

    /** A per-reply speaker button appears only under a finalized, non-empty assistant reply. */
    fun showButton(role: String, content: String, isStreaming: Boolean): Boolean =
        role == "assistant" && !isStreaming && content.isNotBlank()

    /** Whether [messageId] is the reply currently playing. */
    fun isPlaying(messageId: String, activeTtsMessageId: String?): Boolean =
        activeTtsMessageId != null && activeTtsMessageId == messageId

    /** Action to take when a reply's button is tapped. */
    enum class Tap { START, STOP }

    /**
     * Tapping the active reply stops it; tapping any other reply starts it (implicitly stopping the
     * previous one). Only one reply plays at a time.
     */
    fun resolveTap(tappedMessageId: String, activeTtsMessageId: String?): Tap =
        if (tappedMessageId == activeTtsMessageId) Tap.STOP else Tap.START

    /**
     * A completion callback is stale when it carries no id (0 = invalidated by stop/switch/exit) or
     * an id that no longer matches the active speech (a newer reply started).
     */
    fun isStaleCallback(callbackSpeechId: Long, activeSpeechId: Long): Boolean =
        callbackSpeechId == 0L || callbackSpeechId != activeSpeechId
}
