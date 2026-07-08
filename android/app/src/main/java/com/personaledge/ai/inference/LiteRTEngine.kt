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
import android.os.SystemClock
import com.personaledge.ai.models.ModelEntry
import com.personaledge.ai.perf.BackendSelection
import com.personaledge.ai.perf.PerfBackend
import com.personaledge.ai.perf.PerfCalc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

enum class InferenceBackend {
    CPU,
    GPU,
}

private fun InferenceBackend.toPerf(): PerfBackend = when (this) {
    InferenceBackend.CPU -> PerfBackend.CPU
    InferenceBackend.GPU -> PerfBackend.GPU
}

class LiteRTEngine(
    private val context: Context,
) {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var loadedModelId: String? = null

    val isLoaded: Boolean get() = engine != null

    // ---- Stage 0 instrumentation (measurement only; does not affect inference behavior) ----
    // Last-observed monotonic durations, read by the caller after each operation. -1 = not measured.
    @Volatile var lastEngineCreationMs: Long = -1
        private set
    @Volatile var lastEngineInitializationMs: Long = -1
        private set
    @Volatile var lastPromptBuildMs: Long = -1
        private set
    @Volatile var lastConversationCreationMs: Long = -1
        private set
    @Volatile var lastSystemInstructionLength: Int = 0
        private set
    @Volatile var lastBackendSelection: BackendSelection? = null
        private set

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
        val createStart = SystemClock.elapsedRealtimeNanos()
        val newEngine = Engine(config)
        lastEngineCreationMs = PerfCalc.nanosToMs(SystemClock.elapsedRealtimeNanos() - createStart)
        val initStart = SystemClock.elapsedRealtimeNanos()
        newEngine.initialize()
        lastEngineInitializationMs = PerfCalc.nanosToMs(SystemClock.elapsedRealtimeNanos() - initStart)
        engine = newEngine
        loadedModelId = entry.id
        // Direct load has no fallback; requested == attempted == selected.
        lastBackendSelection = BackendSelection(
            requested = backend.toPerf(),
            attempted = backend.toPerf(),
            selected = backend.toPerf(),
            fallbackOccurred = false,
        )
    }

    suspend fun loadModelWithFallback(
        entry: ModelEntry,
        modelFile: File,
        preferGpu: Boolean = true,
    ): InferenceBackend {
        if (preferGpu) {
            try {
                loadModel(entry, modelFile, InferenceBackend.GPU)
                lastBackendSelection = BackendSelection(
                    requested = PerfBackend.GPU,
                    attempted = PerfBackend.GPU,
                    selected = PerfBackend.GPU,
                    fallbackOccurred = false,
                )
                return InferenceBackend.GPU
            } catch (_: Exception) {
                // Behavior unchanged: fall through to CPU. We only record that a fallback happened.
            }
        }
        loadModel(entry, modelFile, InferenceBackend.CPU, useGpuForVision = false)
        lastBackendSelection = BackendSelection(
            requested = if (preferGpu) PerfBackend.GPU else PerfBackend.CPU,
            attempted = if (preferGpu) PerfBackend.GPU else PerfBackend.CPU,
            selected = PerfBackend.CPU,
            fallbackOccurred = preferGpu,
            fallbackReason = if (preferGpu) "gpu_init_failed" else null,
        )
        return InferenceBackend.CPU
    }

    companion object {
        // Balanced sampling for natural, varied prose. Low topK (e.g. 12) tended to make the
        // small model loop and repeat phrases ("an exception – an exception"); a wider topK
        // with moderate temperature reads more naturally and human.
        private val CHAT_SAMPLER = SamplerConfig(topK = 64, topP = 0.9, temperature = 0.75)
        private val VOICE_SAMPLER = SamplerConfig(topK = 64, topP = 0.9, temperature = 0.7)
    }

    suspend fun startConversation(
        memoryContext: MemoryContext,
        history: List<ChatTurn> = emptyList(),
    ) = startConversationInternal(memoryContext, history, buildTimedSystemInstruction(memoryContext, voice = false), CHAT_SAMPLER)

    suspend fun startVoiceConversation(
        memoryContext: MemoryContext,
        history: List<ChatTurn> = emptyList(),
    ) = startConversationInternal(memoryContext, history, buildTimedSystemInstruction(memoryContext, voice = true), VOICE_SAMPLER)

    /**
     * Builds the system instruction exactly as before, but records how long it took and its length.
     * The produced string is identical to calling [PromptBuilder] directly — prompt content is
     * unchanged; this only measures around it.
     */
    private fun buildTimedSystemInstruction(memoryContext: MemoryContext, voice: Boolean): String {
        val start = SystemClock.elapsedRealtimeNanos()
        val instruction = if (voice) {
            PromptBuilder.buildVoiceSystemInstruction(memoryContext)
        } else {
            PromptBuilder.buildSystemInstruction(memoryContext)
        }
        lastPromptBuildMs = PerfCalc.nanosToMs(SystemClock.elapsedRealtimeNanos() - start)
        lastSystemInstructionLength = instruction.length
        return instruction
    }

    private suspend fun startConversationInternal(
        memoryContext: MemoryContext,
        history: List<ChatTurn>,
        systemInstruction: String,
        sampler: SamplerConfig,
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
        // An empty system instruction (Contents.of("")) can make the small model emit empty
        // or broken output, so only set it when the backend actually provides one.
        val convStart = SystemClock.elapsedRealtimeNanos()
        conversation = eng.createConversation(
            if (systemInstruction.isBlank()) {
                ConversationConfig(
                    initialMessages = initialMessages,
                    samplerConfig = sampler,
                )
            } else {
                ConversationConfig(
                    systemInstruction = Contents.of(systemInstruction),
                    initialMessages = initialMessages,
                    samplerConfig = sampler,
                )
            },
        )
        lastConversationCreationMs = PerfCalc.nanosToMs(SystemClock.elapsedRealtimeNanos() - convStart)
    }

    fun hasActiveConversation(): Boolean = conversation != null

    /**
     * Immediately stops the in-flight native generation so the engine is free for the next
     * message. This is the real "dead stop" for Stop AI / End session / barge-in and for the
     * watchdog — coroutine cancellation alone does NOT stop native inference (the litertlm
     * callbackFlow's awaitClose is a no-op), so this native cancelProcess() is required.
     *
     * Safe to call repeatedly and when no generation is running (idempotent). It is intentionally
     * lightweight (no blocking close) so it can be called while the generation lock is held.
     */
    fun cancelGeneration() {
        runCatching { conversation?.cancelProcess() }
    }

    /** Drop the current conversation so the next turn rebuilds a clean one (recovery after a hang). */
    suspend fun endConversation() = withContext(Dispatchers.IO) {
        runCatching { conversation?.cancelProcess() }
        runCatching { conversation?.close() }
        conversation = null
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
