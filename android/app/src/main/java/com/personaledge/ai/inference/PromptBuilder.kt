package com.personaledge.ai.inference

data class MemoryContext(
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val personalityRules: String = "",
    val memoryChunks: List<String> = emptyList(),
) {
    companion object {
        /** Offline fallback only — the backend is the source of truth for personality and limits. */
        const val DEFAULT_SYSTEM_PROMPT =
            "You are CoffeeAI, the user's personal coffee expert and barista assistant. " +
                "Communicate freely and give complete, helpful, accurate answers about coffee, " +
                "recipes, brewing techniques, and operating their coffee machine."
    }
}

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
        // No content or length limits here — the backend system prompt / rules control
        // how much the assistant says. This only adapts delivery for spoken output so
        // text-to-speech sounds natural (no markdown symbols read aloud).
        return buildSystemInstruction(context) + """

            You are speaking out loud in a hands-free voice conversation. Reply in a
            natural, conversational spoken style using plain sentences — no markdown,
            bullet points, numbered lists, or code formatting.
        """.trimIndent()
    }
}
