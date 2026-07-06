package com.personaledge.ai.inference

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.personaledge.ai.models.ModelEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

enum class InferenceBackend {
    CPU,
    GPU,
}

class LiteRTEngine(
    private val context: Context,
) {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var loadedModelId: String? = null

    val isLoaded: Boolean get() = engine != null

    suspend fun loadModel(
        entry: ModelEntry,
        modelFile: File,
        backend: InferenceBackend = InferenceBackend.GPU,
        useGpuForVision: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        if (!modelFile.exists()) {
            error("Model file missing. Download or import ${entry.displayName} again.")
        }
        val minBytes = (entry.sizeBytes * 0.90).toLong().coerceAtLeast(1L)
        if (modelFile.length() < minBytes) {
            error(
                "Model file looks incomplete (${modelFile.length() / 1_000_000} MB of " +
                    "${entry.sizeBytes / 1_000_000} MB). Delete and re-download.",
            )
        }
        close()
        val selectedBackend = when (backend) {
            InferenceBackend.CPU -> Backend.CPU()
            InferenceBackend.GPU -> Backend.GPU()
        }
        val visionBackend = if (entry.supportsVision && useGpuForVision && backend == InferenceBackend.GPU) {
            Backend.GPU()
        } else {
            null
        }
        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = selectedBackend,
            cacheDir = context.cacheDir.absolutePath,
            visionBackend = visionBackend,
            audioBackend = if (entry.supportsAudio) Backend.CPU() else null,
        )
        val newEngine = Engine(config)
        newEngine.initialize()
        engine = newEngine
        loadedModelId = entry.id
    }

    suspend fun loadModelWithFallback(
        entry: ModelEntry,
        modelFile: File,
        preferGpu: Boolean = true,
    ): InferenceBackend {
        if (preferGpu) {
            try {
                loadModel(entry, modelFile, InferenceBackend.GPU)
                return InferenceBackend.GPU
            } catch (_: Exception) {
            }
        }
        loadModel(entry, modelFile, InferenceBackend.CPU, useGpuForVision = false)
        return InferenceBackend.CPU
    }

    suspend fun startConversation(
        memoryContext: MemoryContext,
        history: List<ChatTurn> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        conversation?.close()
        val eng = engine ?: error("Engine not loaded")
        val initialMessages = history.map { turn ->
            when (turn.role) {
                "user" -> Message.user(turn.content)
                "assistant", "model" -> Message.model(turn.content)
                else -> Message.user(turn.content)
            }
        }
        conversation = eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(PromptBuilder.buildSystemInstruction(memoryContext)),
                initialMessages = initialMessages,
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
            ),
        )
    }

    fun sendMessageStreaming(text: String): Flow<String> {
        val conv = conversation ?: error("Conversation not started")
        return conv.sendMessageAsync(text).let { messageFlow ->
            kotlinx.coroutines.flow.flow {
                messageFlow.collect { message ->
                    emit(message.toString())
                }
            }
        }
    }

    suspend fun sendMessage(text: String): String = withContext(Dispatchers.IO) {
        val conv = conversation ?: error("Conversation not started")
        conv.sendMessage(text).toString()
    }

    fun sendMultimodalMessage(
        text: String,
        imagePath: String? = null,
        audioBytes: ByteArray? = null,
    ): Flow<String> {
        val conv = conversation ?: error("Conversation not started")
        val contents = buildList {
            imagePath?.let { add(Content.ImageFile(it)) }
            audioBytes?.let { add(Content.AudioBytes(it)) }
            add(Content.Text(text))
        }
        return conv.sendMessageAsync(Contents.of(contents)).let { messageFlow ->
            kotlinx.coroutines.flow.flow {
                messageFlow.collect { message ->
                    emit(message.toString())
                }
            }
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        loadedModelId = null
    }

    fun loadedModelId(): String? = loadedModelId
}
