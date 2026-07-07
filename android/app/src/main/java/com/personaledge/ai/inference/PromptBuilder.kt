package com.personaledge.ai.inference

data class MemoryContext(
    val systemPrompt: String = "You are a helpful personal assistant.",
    val personalityRules: String = "",
    val memoryChunks: List<String> = emptyList(),
)

data class ChatTurn(
    val role: String,
    val content: String,
)

object PromptBuilder {
    fun buildSystemInstruction(context: MemoryContext): String {
        val parts = buildList {
            add(context.systemPrompt.trim())
            if (context.personalityRules.isNotBlank()) {
                add("Personality and tone rules:\n${context.personalityRules.trim()}")
            }
            if (context.memoryChunks.isNotEmpty()) {
                add("Relevant memory:\n${context.memoryChunks.joinToString("\n") { "- $it" }}")
            }
        }
        return parts.joinToString("\n\n")
    }

    fun buildVoiceSystemInstruction(context: MemoryContext): String {
        return buildSystemInstruction(context) + """

            Voice conversation mode:
            - Reply in 1–3 short spoken sentences (under 40 words total when possible).
            - Use plain conversational language — no markdown, bullets, or code blocks.
            - Get to the point immediately; skip preamble.
        """.trimIndent()
    }
}
