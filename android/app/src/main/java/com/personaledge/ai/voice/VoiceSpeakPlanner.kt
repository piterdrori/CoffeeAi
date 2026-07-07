package com.personaledge.ai.voice

/**
 * Picks the next TTS chunk from a streaming assistant reply so speech can start
 * before generation finishes.
 */
object VoiceSpeakPlanner {
    private val sentenceBreak = Regex("(?<=[.!?])\\s+")

    /**
     * @param text Full assistant text so far
     * @param spokenLength Chars already sent to TTS
     * @param stillStreaming Whether the model is still generating
     */
    fun nextChunk(text: String, spokenLength: Int, stillStreaming: Boolean): Pair<String, Int>? {
        if (text.length <= spokenLength) return null
        val tail = text.substring(spokenLength)
        val remaining = tail.trimStart()
        if (remaining.isBlank()) return null
        val offset = tail.length - remaining.length

        val chunk = when {
            spokenLength == 0 -> firstChunk(remaining, stillStreaming)
            !stillStreaming -> remaining.trim().takeIf { it.isNotBlank() }
            else -> followChunk(remaining)
        } ?: return null

        return chunk to (spokenLength + offset + chunk.length)
    }

    private fun firstChunk(remaining: String, stillStreaming: Boolean): String? {
        val firstSentence = sentenceBreak.split(remaining, limit = 2).firstOrNull()?.trim().orEmpty()
        if (firstSentence.length >= 25) return firstSentence
        if (!stillStreaming && remaining.isNotBlank()) return remaining.trim()
        if (remaining.length >= 50) {
            val cut = remaining.take(50).lastIndexOf(' ').takeIf { it > 20 } ?: 50
            return remaining.take(cut).trim() + "…"
        }
        return null
    }

    private fun followChunk(remaining: String): String? {
        val nextSentence = sentenceBreak.split(remaining, limit = 2).firstOrNull()?.trim().orEmpty()
        if (nextSentence.length >= 20) return nextSentence
        if (remaining.length >= 60) {
            val cut = remaining.take(60).lastIndexOf(' ').takeIf { it > 15 } ?: 60
            return remaining.take(cut).trim() + "…"
        }
        return null
    }
}
