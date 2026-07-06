package com.personaledge.ai.models

data class ModelEntry(
    val id: String,
    val displayName: String,
    val description: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val isGated: Boolean,
    val supportsVision: Boolean,
    val supportsAudio: Boolean,
    val minRamGb: Int,
)

object ModelCatalog {
    const val GEMMA3_1B_ID = "gemma3-1b-it"
    const val GEMMA4_E2B_ID = "gemma-4-e2b-it"

    val models = listOf(
        ModelEntry(
            id = GEMMA3_1B_ID,
            displayName = "Gemma 3 1B IT",
            description = "Fast text chat, low RAM (~530 MB)",
            fileName = "gemma3-1b-it-int4.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
            sizeBytes = 620_000_000L,
            isGated = true,
            supportsVision = false,
            supportsAudio = false,
            minRamGb = 4,
        ),
        ModelEntry(
            id = GEMMA4_E2B_ID,
            displayName = "Gemma 4 E2B IT",
            description = "Multimodal chat, images, and audio (~2.6 GB)",
            fileName = "gemma-4-E2B-it.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            sizeBytes = 2_600_000_000L,
            isGated = false,
            supportsVision = true,
            supportsAudio = true,
            minRamGb = 6,
        ),
    )

    fun findById(id: String): ModelEntry? = models.find { it.id == id }

    fun defaultModel(): ModelEntry = models.first()
}
