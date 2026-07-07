package com.personaledge.ai.inference

data class MemoryContext(
    // The app hardcodes NO persona/prompt. Everything the LLM is told comes from the
    // backend config (system_prompt + rules) and memory. Empty here means "no direction"
    // until the backend config is available.
    val systemPrompt: String = "",
    val personalityRules: String = "",
    val memoryChunks: List<String> = emptyList(),
)

data class ChatTurn(
    val role: String,
    val content: String,
)

object PromptBuilder {
    /**
     * Assembles the system instruction purely from backend-provided values. The app adds
     * no instructions of its own about how the LLM should talk — that is defined entirely
     * in the backend config.
     */
    fun buildSystemInstruction(context: MemoryContext): String {
        val parts = buildList {
            if (context.systemPrompt.isNotBlank()) add(context.systemPrompt.trim())
            if (context.personalityRules.isNotBlank()) add(context.personalityRules.trim())
            if (context.memoryChunks.isNotEmpty()) {
                add("Relevant memory:\n${context.memoryChunks.joinToString("\n") { "- $it" }}")
            }
        }
        return parts.joinToString("\n\n")
    }

    /** Voice uses the same backend-defined instruction as chat — no code-added prompt. */
    fun buildVoiceSystemInstruction(context: MemoryContext): String =
        buildSystemInstruction(context)
}
