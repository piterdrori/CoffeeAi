package com.personaledge.ai.chat

import com.personaledge.ai.inference.ChatTurn

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
