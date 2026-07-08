package com.personaledge.ai.chat

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personaledge.ai.EdgeAiApplication
import com.personaledge.ai.coffee.BrewState
import com.personaledge.ai.coffee.BrewStatus
import com.personaledge.ai.coffee.MachineCommand
import android.os.SystemClock
import com.personaledge.ai.inference.ChatTurn
import com.personaledge.ai.inference.InferenceBackend
import com.personaledge.ai.inference.MemoryContext
import com.personaledge.ai.inference.LiteRTEngine
import com.personaledge.ai.models.ModelCatalog
import com.personaledge.ai.models.ModelEntry
import com.personaledge.ai.perf.CompletionStatus
import com.personaledge.ai.perf.ErrorCategory
import com.personaledge.ai.perf.InferenceMetricsRecorder
import com.personaledge.ai.perf.PerfBackend
import com.personaledge.ai.perf.PerfCalc
import com.personaledge.ai.perf.PerfLog
import com.personaledge.ai.ui.components.OrbState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class UiMessage(
    val role: String,
    val content: String,
    val isStreaming: Boolean = false,
    val imageUri: String? = null,
    val id: String = UUID.randomUUID().toString(),
)

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val isEngineReady: Boolean = false,
    val error: String? = null,
    val activeModelName: String? = null,
    val activeModelId: String? = null,
    val engineStatus: String = "Not loaded",
    val memoryConnected: Boolean = false,
    val pendingImagePath: String? = null,
    val pendingImageUri: String? = null,
    val orbState: OrbState = OrbState.Idle,
)

/** Authoritative lifecycle phase for a voice turn, owned by [ChatViewModel]. */
enum class VoicePhase {
    /** Voice screen closed / not in a session. */
    Inactive,
    /** Microphone open, waiting for the user to speak. */
    Listening,
    /** A turn is generating a reply. */
    Generating,
    /** A non-blank reply is finalized and ready to be spoken (durable — survives collector gaps). */
    ReadyToSpeak,
    /** TTS is actively speaking the reply. */
    Speaking,
    /** Nothing usable (blank / timeout / failure) — a visible notice, then back to listening. */
    Error,
}

/**
 * Durable, ViewModel-owned voice state. Unlike a one-shot event, this is a [StateFlow] value that
 * always holds the latest turn outcome, so a completed reply ([VoicePhase.ReadyToSpeak] with
 * [finalText]) remains observable even if the UI collector momentarily disappears (recomposition,
 * screen exit/re-entry). [sequence] increments on each terminal outcome so the UI can start TTS at
 * most once per turn (idempotent) and reject stale outcomes after Stop AI.
 */
data class VoiceTurnState(
    val sessionId: String? = null,
    val turnId: Long = 0,
    val assistantMessageId: String? = null,
    val phase: VoicePhase = VoicePhase.Inactive,
    val finalText: String? = null,
    val error: String? = null,
    val speechRequested: Boolean = false,
    val sequence: Long = 0,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EdgeAiApplication
    private val modelRepository = app.modelRepository
    private val syncClient = app.syncClient
    private val sessionStore = app.chatSessionStore
    private val engine = LiteRTEngine(application)

    private var currentSessionId: String? = null
    private var sendJob: Job? = null
    private var machineCommandJob: Job? = null

    // Stage 0 instrumentation: the in-flight turn's recorder (so external cancel can stamp latency)
    // and the most recent model-preparation duration. Measurement only — no behavior change.
    @Volatile private var activeRecorder: InferenceMetricsRecorder? = null
    @Volatile private var lastModelPrepMs: Long = 0

    private companion object {
        const val TAG = "CoffeeGen"
        const val STREAM_UI_THROTTLE_MS = 80L
        // Prefill + first token can be slow on CPU with long history, so this budget is generous.
        // The inter-token budget is what actually catches a real stall once tokens are flowing.
        const val FIRST_TOKEN_TIMEOUT_MS = 40_000L
        const val INTER_TOKEN_TIMEOUT_MS = 12_000L
    }

    // ---------------- Single-flight generation lifecycle (shared by text + voice + commands) -----

    // Native inference is single-session; turns must not overlap.
    private val generationMutex = Mutex()
    // Monotonic turn id. A callback may only touch shared UI state while its turn is the active one.
    private val turnSeq = AtomicLong(0L)
    private val activeTurnId = AtomicLong(0L)

    private fun beginTurn(): Long {
        val id = turnSeq.incrementAndGet()
        activeTurnId.set(id)
        return id
    }

    private fun isActiveTurn(turnId: Long): Boolean = activeTurnId.get() == turnId

    private fun invalidateTurns() {
        activeTurnId.set(0L)
    }

    private enum class GenTimeout { NONE, FIRST_TOKEN, INTER_TOKEN }
    private data class GenResult(
        val text: String,
        val timeout: GenTimeout,
        val firstTokenMs: Long,
        val tokenCount: Int,
    )

    // Durable voice state (see [VoiceTurnState]). Replaces the previous one-shot SharedFlow so a
    // finished reply is never lost when no collector happens to be subscribed at the emit moment.
    private val _voiceTurn = MutableStateFlow(VoiceTurnState())
    val voiceTurn: StateFlow<VoiceTurnState> = _voiceTurn.asStateFlow()
    private val voiceSeq = AtomicLong(0L)

    private fun setVoiceReadyToSpeak(turnId: Long, assistantId: String, text: String) {
        _voiceTurn.value = VoiceTurnState(
            sessionId = currentSessionId,
            turnId = turnId,
            assistantMessageId = assistantId,
            phase = VoicePhase.ReadyToSpeak,
            finalText = text,
            error = null,
            speechRequested = false,
            sequence = voiceSeq.incrementAndGet(),
        )
    }

    private fun setVoiceError(message: String) {
        _voiceTurn.update {
            it.copy(
                phase = VoicePhase.Error,
                finalText = null,
                error = message,
                speechRequested = false,
                sequence = voiceSeq.incrementAndGet(),
            )
        }
    }

    /** Called by the voice UI when it begins actually speaking the ready reply. */
    fun markVoiceSpeaking() {
        _voiceTurn.update { if (it.phase == VoicePhase.Inactive) it else it.copy(phase = VoicePhase.Speaking, speechRequested = true) }
    }

    /** Called by the voice UI when it returns the mic to listening (TTS done / read-aloud off). */
    fun markVoiceListening() {
        _voiceTurn.update {
            if (it.phase == VoicePhase.Inactive) it
            else it.copy(phase = VoicePhase.Listening, finalText = null, error = null, speechRequested = false)
        }
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _brewState = MutableStateFlow(BrewState())
    val brewState: StateFlow<BrewState> = _brewState.asStateFlow()

    val activeSessionId: String? get() = currentSessionId

    init {
        viewModelScope.launch {
            modelRepository.activeModelId.distinctUntilChanged().collect {
                loadActiveModel()
            }
        }
    }

    private fun cleanReply(text: String): String =
        text
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace("*", "")
            .replace("`", "")
            .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*[-•]\\s+"), "")

    /** Update the assistant message identified by [assistantId]. A stale/removed id is ignored. */
    private fun updateAssistantMessage(assistantId: String, transform: (UiMessage) -> UiMessage) {
        _uiState.update { state ->
            val idx = state.messages.indexOfFirst { it.id == assistantId }
            if (idx < 0) return@update state
            val updated = state.messages.toMutableList()
            updated[idx] = transform(updated[idx])
            state.copy(messages = updated)
        }
    }

    private fun removeMessage(messageId: String) {
        _uiState.update { state ->
            state.copy(messages = state.messages.filterNot { it.id == messageId })
        }
    }

    private fun setIdleState() {
        _uiState.update { it.copy(isLoading = false, orbState = OrbState.Idle) }
    }

    private fun timeoutError(timeout: GenTimeout): String = when (timeout) {
        GenTimeout.FIRST_TOKEN -> "CoffeeAI took too long to start responding. Please try again."
        GenTimeout.INTER_TOKEN -> "The response stalled. Please try again."
        GenTimeout.NONE -> "No response — please try again."
    }

    /**
     * Runs a single turn under the single-flight lock with two watchdogs:
     *  - first-token/prefill timeout (covers conversation creation + first token),
     *  - inter-token idle timeout (armed only after the first token arrives).
     * Tokens append to [assistantId] and only while [turnId] is the active turn.
     * On timeout, native generation is stopped with cancelProcess() (coroutine cancel alone
     * does NOT stop native inference — verified against the litertlm callbackFlow).
     */
    private suspend fun runGeneration(
        turnId: Long,
        assistantId: String,
        startConversation: suspend () -> Unit,
        makeFlow: () -> Flow<String>,
        includeOrb: Boolean,
        recorder: InferenceMetricsRecorder? = null,
        promptCharsBase: Int = 0,
    ): GenResult = generationMutex.withLock {
        val builder = StringBuilder()
        var lastUiMs = 0L
        val startMs = System.currentTimeMillis()
        var firstTokenAtMs = 0L
        var lastTokenMs = startMs
        var tokenCount = 0
        var timeout = GenTimeout.NONE

        coroutineScope {
            val generation = launch {
                startConversation()
                // Conversation/prompt/backend timings are produced by LiteRTEngine during
                // startConversation(); read them here (measurement only).
                recorder?.setConversationCreation(engine.lastConversationCreationMs)
                recorder?.setPromptBuild(engine.lastPromptBuildMs, engine.lastSystemInstructionLength + promptCharsBase)
                engine.lastBackendSelection?.let { recorder?.setBackendSelection(it) }
                recorder?.markGenerationStart()
                makeFlow().collect { token ->
                    recorder?.onStreamEvent(token)
                    if (!isActiveTurn(turnId)) return@collect
                    if (firstTokenAtMs == 0L && token.isNotEmpty()) {
                        firstTokenAtMs = System.currentTimeMillis()
                    }
                    lastTokenMs = System.currentTimeMillis()
                    tokenCount++
                    builder.append(token)
                    val now = System.currentTimeMillis()
                    if (now - lastUiMs >= STREAM_UI_THROTTLE_MS) {
                        lastUiMs = now
                        val snapshot = cleanReply(builder.toString())
                        updateAssistantMessage(assistantId) {
                            it.copy(content = snapshot, isStreaming = true)
                        }
                        if (includeOrb && isActiveTurn(turnId)) {
                            _uiState.update { it.copy(orbState = OrbState.Thinking) }
                        }
                    }
                }
            }
            val watchdog = launch {
                while (generation.isActive) {
                    delay(500)
                    val now = System.currentTimeMillis()
                    if (firstTokenAtMs == 0L) {
                        if (now - startMs > FIRST_TOKEN_TIMEOUT_MS) {
                            timeout = GenTimeout.FIRST_TOKEN
                            recorder?.markCancellationRequested()
                            engine.cancelGeneration()
                            generation.cancel()
                            break
                        }
                    } else if (now - lastTokenMs > INTER_TOKEN_TIMEOUT_MS) {
                        timeout = GenTimeout.INTER_TOKEN
                        recorder?.markCancellationRequested()
                        engine.cancelGeneration()
                        generation.cancel()
                        break
                    }
                }
            }
            generation.join()
            watchdog.cancel()
        }
        val firstMs = if (firstTokenAtMs == 0L) -1L else firstTokenAtMs - startMs
        GenResult(builder.toString(), timeout, firstMs, tokenCount)
    }

    /** Applies a completed turn's result — only if the turn is still active. */
    private fun finishTurn(
        turnId: Long,
        assistantId: String,
        prompt: String,
        result: GenResult,
        isVoice: Boolean,
    ) {
        if (!isActiveTurn(turnId)) {
            Log.i(TAG, "turn=$turnId stale-finish rejected (active=${activeTurnId.get()})")
            updateAssistantMessage(assistantId) { it.copy(isStreaming = false) }
            return
        }
        val clean = cleanReply(result.text).trim()
        Log.i(
            TAG,
            "turn=$turnId done msg=$assistantId firstTokenMs=${result.firstTokenMs} tokens=${result.tokenCount} " +
                "timeout=${result.timeout} respLen=${clean.length} respHash=${clean.hashCode()}",
        )
        if (clean.isNotBlank()) {
            updateAssistantMessage(assistantId) { it.copy(content = clean, isStreaming = false) }
            setIdleState()
            invalidateTurns()
            // Voice success: publish a DURABLE ready-to-speak state (not a one-shot event). The
            // voice UI reconciles from this StateFlow, so a finished turn can never stay stuck on
            // Thinking even if the collector was momentarily gone.
            if (isVoice) {
                Log.i(TAG, "turn=$turnId voicePhase=ReadyToSpeak msg=$assistantId len=${clean.length}")
                setVoiceReadyToSpeak(turnId, assistantId, clean)
            }
            viewModelScope.launch { syncClient.syncTurn(prompt, clean) }
        } else {
            // Blank reply: never persist, never feed to history, never speak a canned line.
            removeMessage(assistantId)
            setIdleState()
            invalidateTurns()
            if (isVoice) {
                Log.i(TAG, "turn=$turnId voicePhase=Error(blank) timeout=${result.timeout}")
                setVoiceError("I didn't catch a reply. Please try again.")
            } else {
                _uiState.update { it.copy(error = timeoutError(result.timeout)) }
            }
        }
        viewModelScope.launch { persistCurrentSession() }
    }

    /** Cleans up after an exception during a turn (never leaves a blank streaming bubble). */
    private fun failTurn(turnId: Long, assistantId: String, e: Throwable, isVoice: Boolean) {
        engine.cancelGeneration()
        if (!isActiveTurn(turnId)) return
        removeMessage(assistantId)
        invalidateTurns()
        _uiState.update {
            it.copy(isLoading = false, orbState = OrbState.Idle, error = e.message ?: "Generation failed")
        }
        Log.w(TAG, "turn=$turnId failed msg=$assistantId: ${e.message}")
        if (isVoice) setVoiceError("Something went wrong. Please try again.")
    }

    fun loadSession(sessionId: String) {
        if (currentSessionId == sessionId && _uiState.value.messages.isNotEmpty()) return
        // Switching sessions while a turn is generating: stop and invalidate it so its late
        // callbacks cannot touch the newly loaded session's message list.
        val sJob = sendJob
        val mJob = machineCommandJob
        sendJob = null
        machineCommandJob = null
        resetGenerationUiState(cancelNative = true, clearError = true)
        sJob?.cancel()
        mJob?.cancel()
        currentSessionId = sessionId
        viewModelScope.launch { loadSessionInternal(sessionId) }
    }

    suspend fun prepareNewSession(sessionId: String = UUID.randomUUID().toString()): String {
        sessionStore.deleteSessionsWithoutMessages()
        val draft = sessionStore.findDraftSession()
        val id = draft?.id ?: sessionId
        currentSessionId = id
        clearChatInternal()
        return id
    }

    private suspend fun ensureSessionInStore() {
        val sessionId = currentSessionId ?: return
        if (sessionStore.getSession(sessionId) != null) return
        val accent = sessionId.hashCode().mod(4).let { if (it < 0) -it else it }
        sessionStore.createSession(sessionId, "New Chat", "", accent)
    }

    fun persistSession() {
        viewModelScope.launch { persistCurrentSession() }
    }

    private suspend fun loadSessionInternal(sessionId: String) {
        val stored = sessionStore.messagesFor(sessionId)
        val messages = stored.map { UiMessage(it.role, it.content, imageUri = it.imageUri) }
        _uiState.update {
            it.copy(messages = messages, inputText = "", error = null, pendingImagePath = null, pendingImageUri = null)
        }
        if (engine.isLoaded) {
            val memory = syncClient.offlineContext()
            engine.startConversation(memory, ChatLogic.selectHistory(messages).turns)
        }
    }

    private suspend fun persistCurrentSession() {
        val sessionId = currentSessionId ?: return
        val messages = ChatLogic.persistableMessages(_uiState.value.messages)
        if (messages.isEmpty()) {
            sessionStore.deleteSession(sessionId)
            return
        }
        ensureSessionInStore()
        sessionStore.saveMessages(sessionId, messages)
        val firstUser = messages.firstOrNull { it.role == "user" && it.content.isNotBlank() }?.content?.trim()
        val title = firstUser?.let { snippet ->
            if (snippet.length > 48) "${snippet.take(48)}…" else snippet
        } ?: "New Chat"
        val preview = messages.lastOrNull { it.content.isNotBlank() }?.content?.trim()?.let { text ->
            if (text.length > 80) "${text.take(80)}…" else text
        } ?: ""
        sessionStore.updateSessionMeta(sessionId, title, preview)
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun loadActiveModel() {
        viewModelScope.launch { loadActiveModelInternal() }
    }

    private suspend fun loadActiveModelInternal() {
        val preferGpu = syncClient.getBackendConfig().useGpu
        val activeId = modelRepository.activeModelId.first()
        val primary = activeId?.let { ModelCatalog.findById(it) }
            ?: ModelCatalog.models.firstOrNull { modelRepository.isDownloaded(it) }
            ?: ModelCatalog.defaultModel()

        // Engine already loaded and ready with this model — do NOT reload it.
        if (engine.isLoaded && engine.loadedModelId() == primary.id && _uiState.value.isEngineReady) {
            if (!engine.hasActiveConversation()) {
                runCatching {
                    val memory = syncClient.offlineContext()
                    engine.startConversation(memory)
                }
            }
            return
        }

        _uiState.update { it.copy(error = null, engineStatus = "Loading…") }

        if (primary.isBundled && !modelRepository.hasBundledAsset(primary)) {
            _uiState.update {
                it.copy(
                    isEngineReady = false,
                    activeModelName = primary.displayName,
                    activeModelId = primary.id,
                    engineStatus = "Built-in model missing",
                    error = "Rebuild the APK with the bundled Gemma model (see scripts/fetch-bundled-gemma-model.ps1).",
                )
            }
            return
        }

        if (primary.isBundled && !modelRepository.isInstalledOnDisk(primary)) {
            _uiState.update {
                it.copy(
                    engineStatus = "Installing built-in model…",
                    activeModelName = primary.displayName,
                    activeModelId = primary.id,
                )
            }
            val prepStart = SystemClock.elapsedRealtimeNanos()
            val prepared = modelRepository.ensureOnDisk(primary)
            lastModelPrepMs = PerfCalc.nanosToMs(SystemClock.elapsedRealtimeNanos() - prepStart)
            if (!prepared) {
                _uiState.update {
                    it.copy(
                        isEngineReady = false,
                        engineStatus = "Install failed",
                        error = "Could not install ${primary.displayName} from app package.",
                    )
                }
                return
            }
        }

        if (!modelRepository.isInstalledOnDisk(primary)) {
            val partial = modelRepository.getDownloadedSize(primary)
            val msg = if (partial > 0) {
                "${primary.displayName} is still installing. Please wait a moment and try again."
            } else if (primary.isBundled) {
                "CoffeeAI is preparing its built-in model. This can take a few minutes on first launch."
            } else {
                "${primary.displayName} is not available yet."
            }
            _uiState.update {
                it.copy(
                    isEngineReady = false,
                    activeModelName = primary.displayName,
                    activeModelId = primary.id,
                    engineStatus = if (partial > 0) "Incomplete download" else "Not downloaded",
                    error = msg,
                )
            }
            return
        }

        val loadResult = tryLoadEntry(primary, preferGpu)
        if (loadResult.isSuccess) {
            applyLoadedModel(primary, loadResult.getOrThrow())
            return
        }

        val primaryError = loadResult.exceptionOrNull() ?: Exception("Failed to load model")
        val fallback = ModelCatalog.models.firstOrNull {
            it.id != primary.id && modelRepository.isInstalledOnDisk(it)
        }
        if (fallback != null) {
            val fallbackResult = tryLoadEntry(fallback, preferGpu)
            if (fallbackResult.isSuccess) {
                modelRepository.setActiveModel(fallback.id)
                applyLoadedModel(
                    fallback,
                    fallbackResult.getOrThrow(),
                    switchedFrom = primary.displayName,
                    switchReason = primaryError.message,
                )
                return
            }
        }

        val hint = engineLoadHint(primary, primaryError)
        _uiState.update {
            it.copy(
                isEngineReady = false,
                activeModelName = primary.displayName,
                activeModelId = primary.id,
                engineStatus = "Failed to load",
                error = "${primaryError.message ?: "Failed to load model"}. $hint",
            )
        }
    }

    private suspend fun tryLoadEntry(entry: ModelEntry, preferGpu: Boolean): Result<MemoryContext?> {
        val loadRecorder = PerfLog.newModelLoadRecorder(
            context = app,
            modelId = entry.id,
            modelFileName = entry.fileName,
            modelFileSizeBytes = entry.sizeBytes,
            requestedBackend = if (preferGpu) PerfBackend.GPU else PerfBackend.CPU,
        )
        loadRecorder.modelPreparationMs = lastModelPrepMs
        loadRecorder.captureMemory("beforeModelLoad")
        loadRecorder.captureThermal("beforeModelLoad")
        return try {
            val backend = engine.loadModelWithFallback(entry, modelRepository.getModelFile(entry), preferGpu = preferGpu)
            loadRecorder.engineCreationMs = engine.lastEngineCreationMs
            loadRecorder.engineInitializationMs = engine.lastEngineInitializationMs
            engine.lastBackendSelection?.let { loadRecorder.setBackendSelection(it) }
            loadRecorder.captureMemory("afterModelLoad")
            loadRecorder.captureThermal("afterModelLoad")
            if (preferGpu && backend == InferenceBackend.CPU) {
                syncClient.disableGpu()
            }
            val memory = syncClient.offlineContext()
            engine.startConversation(memory)
            loadRecorder.conversationCreationMs = engine.lastConversationCreationMs
            loadRecorder.finish(CompletionStatus.SUCCESS)
            Result.success(memory)
        } catch (e: Exception) {
            loadRecorder.finish(CompletionStatus.ERROR, ErrorCategory.LOAD_FAILURE)
            Result.failure(e)
        }
    }

    private fun applyLoadedModel(
        entry: ModelEntry,
        memory: MemoryContext?,
        switchedFrom: String? = null,
        switchReason: String? = null,
    ) {
        val note = if (switchedFrom != null) {
            "Using ${entry.displayName} (${switchedFrom} failed: ${switchReason ?: "load error"})"
        } else {
            null
        }
        _uiState.update {
            it.copy(
                isEngineReady = true,
                activeModelName = entry.displayName,
                activeModelId = entry.id,
                engineStatus = "Ready",
                memoryConnected = memory != null,
                orbState = OrbState.Idle,
                error = note,
            )
        }
    }

    private fun engineLoadHint(entry: ModelEntry, e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("PREFILL_DECODE", ignoreCase = true) ->
                "Try Gemma 3 1B, or delete ${entry.displayName} and re-download the .litertlm file."
            msg.contains("incomplete", ignoreCase = true) ->
                "Re-download the model from Settings."
            else -> "Try Settings → turn off GPU, or switch to Gemma 3 1B."
        }
    }

    fun attachImage(uri: Uri, context: android.content.Context) {
        viewModelScope.launch {
            try {
                val dest = File(context.cacheDir, "chat_image_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
                _uiState.update {
                    it.copy(
                        pendingImagePath = dest.absolutePath,
                        pendingImageUri = uri.toString(),
                    )
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(error = "Could not attach image") }
            }
        }
    }

    fun clearPendingImage() {
        _uiState.update { it.copy(pendingImagePath = null, pendingImageUri = null) }
    }

    /**
     * Single cleanup helper for active-generation UI state. Invalidates the active turn (so late
     * callbacks are rejected), optionally stops native generation, removes blank streaming
     * placeholders, finalizes any remaining streaming message, and returns the UI to idle.
     * It does NOT cancel coroutine jobs — callers cancel the specific job (sendJob /
     * machineCommandJob) to avoid cancelling themselves recursively.
     */
    private fun resetGenerationUiState(cancelNative: Boolean = true, clearError: Boolean = false) {
        invalidateTurns()
        if (cancelNative) engine.cancelGeneration()
        _uiState.update { state ->
            state.copy(
                messages = ChatLogic.clearedStreamingMessages(state.messages),
                isLoading = false,
                orbState = OrbState.Idle,
                error = if (clearError) null else state.error,
            )
        }
    }

    /**
     * Cancels the in-flight turn deterministically: mark the turn inactive (so late callbacks are
     * rejected), stop native generation, clean the UI, then cancel the coroutine. Idempotent.
     */
    fun cancelResponse() {
        Log.i(TAG, "cancelResponse (active=${activeTurnId.get()})")
        activeRecorder?.markCancellationRequested()
        val job = sendJob
        sendJob = null
        resetGenerationUiState(cancelNative = true, clearError = true)
        job?.cancel()
        // Stop AI / barge-in during a voice session: clear any durable ReadyToSpeak so an
        // interrupted answer can never begin speaking after the user stopped it.
        _voiceTurn.update {
            if (it.phase == VoicePhase.Inactive) it
            else it.copy(phase = VoicePhase.Listening, finalText = null, error = null, speechRequested = false)
        }
    }

    fun beginVoiceSession() {
        // Ensure the model is loaded; the conversation is (re)built fresh for each turn.
        _voiceTurn.update { it.copy(phase = VoicePhase.Listening, finalText = null, error = null, speechRequested = false) }
        viewModelScope.launch {
            if (!_uiState.value.isEngineReady) {
                loadActiveModelInternal()
            }
        }
    }

    fun endVoiceSession() {
        // Voice screen closing — make sure no turn keeps running or writes back.
        activeRecorder?.markCancellationRequested()
        invalidateTurns()
        engine.cancelGeneration()
        _voiceTurn.value = VoiceTurnState()
    }

    fun sendVoiceMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank() || _uiState.value.isLoading) return
        startTurn(prompt = text, imagePath = null, isVoice = true)
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        val imagePath = _uiState.value.pendingImagePath
        if ((text.isBlank() && imagePath == null) || _uiState.value.isLoading) return
        val prompt = text.ifBlank { "Describe this image." }
        startTurn(prompt = prompt, imagePath = imagePath, isVoice = false)
    }

    /** Shared entry point for text and voice turns. */
    private fun startTurn(prompt: String, imagePath: String?, isVoice: Boolean) {
        sendJob?.cancel()
        val turnId = beginTurn()
        val assistantId = UUID.randomUUID().toString()
        val pendingImageUri = _uiState.value.pendingImageUri
        sendJob = viewModelScope.launch {
            var recorder: InferenceMetricsRecorder? = null
            try {
                ensureSessionInStore()
                if (!_uiState.value.isEngineReady) {
                    loadActiveModelInternal()
                    if (!_uiState.value.isEngineReady) {
                        invalidateTurns()
                        val err = _uiState.value.error ?: "CoffeeAI is still starting up. Please wait a moment."
                        _uiState.update {
                            it.copy(
                                inputText = "",
                                isLoading = false,
                                orbState = OrbState.Idle,
                                messages = it.messages + UiMessage("user", prompt, imageUri = pendingImageUri),
                                error = err,
                                pendingImagePath = null,
                                pendingImageUri = null,
                            )
                        }
                        if (isVoice) setVoiceError(err)
                        return@launch
                    }
                }

                // History = everything BEFORE this turn's user message. Bounded by the history
                // budget so the per-turn re-prefill (and first-token latency) stays stable as the
                // visible transcript grows. The new prompt is sent separately (appears exactly
                // once). Offline: no rolling summary / backend memory (recent-window only).
                val priorMessages = _uiState.value.messages
                val selectedHistory = ChatLogic.selectHistory(priorMessages)
                val history = selectedHistory.turns

                val modelEntry = _uiState.value.activeModelId?.let { ModelCatalog.findById(it) }
                    ?: ModelCatalog.defaultModel()
                val rec = PerfLog.newTurnRecorder(
                    context = app,
                    localTurnId = turnId,
                    sessionId = currentSessionId,
                    modelId = modelEntry.id,
                    modelFileName = modelEntry.fileName,
                    modelFileSizeBytes = modelEntry.sizeBytes,
                )
                recorder = rec
                activeRecorder = rec

                // Behavior-identical prefetch: voice uses offline context; text uses the measured
                // quick prefetch and falls back to offline context on null — same as before.
                val memory = if (isVoice) {
                    val offStart = SystemClock.elapsedRealtimeNanos()
                    val m = syncClient.offlineContext()
                    rec.setOfflineFallback(PerfCalc.nanosToMs(SystemClock.elapsedRealtimeNanos() - offStart))
                    m
                } else {
                    val res = syncClient.prefetchQuickMeasured(prompt)
                    rec.setPrefetch(
                        res.telemetry.outcome,
                        res.telemetry.latencyMs,
                        res.telemetry.chunkCount,
                        res.telemetry.contextCharacterCount,
                    )
                    res.context ?: run {
                        val offStart = SystemClock.elapsedRealtimeNanos()
                        val m = syncClient.offlineContext()
                        rec.setOfflineFallback(PerfCalc.nanosToMs(SystemClock.elapsedRealtimeNanos() - offStart))
                        m
                    }
                }

                _uiState.update {
                    it.copy(
                        inputText = "",
                        isLoading = true,
                        orbState = OrbState.Thinking,
                        messages = it.messages +
                            UiMessage("user", prompt, imageUri = pendingImageUri) +
                            UiMessage(id = assistantId, role = "assistant", content = "", isStreaming = true),
                        error = null,
                        memoryConnected = memory.memoryChunks.isNotEmpty(),
                        pendingImagePath = null,
                        pendingImageUri = null,
                    )
                }

                if (isVoice) {
                    _voiceTurn.update {
                        it.copy(
                            sessionId = currentSessionId,
                            turnId = turnId,
                            assistantMessageId = assistantId,
                            phase = VoicePhase.Generating,
                            finalText = null,
                            error = null,
                            speechRequested = false,
                        )
                    }
                }

                Log.i(
                    TAG,
                    "turn=$turnId start msg=$assistantId voice=$isVoice model=${_uiState.value.activeModelId} " +
                        "promptLen=${prompt.length} promptHash=${prompt.hashCode()}",
                )
                // History/prefill diagnostics — counts and lengths only, never raw text. Combined
                // with the perf-recorder turn metrics (conv-create, first-token, decode) this lets
                // latency-vs-context growth be measured turn over turn.
                val promptTokens = PerfCalc.estimateTokens(prompt.length)
                val systemTokens = PerfCalc.estimateTokens(engine.lastSystemInstructionLength)
                Log.i(
                    TAG,
                    "turn=$turnId history stored=${selectedHistory.totalStoredMessages} " +
                        "valid=${selectedHistory.validMessages} selected=${selectedHistory.selectedCount} " +
                        "dropped=${selectedHistory.droppedCount} trimmed=${selectedHistory.trimmedMessageCount} " +
                        "histChars=${selectedHistory.selectedChars} histTok=${selectedHistory.historyTokens} " +
                        "summaryChars=${selectedHistory.summaryChars} summaryTok=${selectedHistory.summaryTokens} " +
                        "memChars=${selectedHistory.memoryChars} memTok=${selectedHistory.memoryTokens} " +
                        "promptTok=$promptTokens estInputTok=${systemTokens + selectedHistory.historyTokens + selectedHistory.summaryTokens + selectedHistory.memoryTokens + promptTokens}",
                )

                val result = runGeneration(
                    turnId = turnId,
                    assistantId = assistantId,
                    startConversation = {
                        if (isVoice) engine.startVoiceConversation(memory, history)
                        else engine.startConversation(memory, history)
                    },
                    makeFlow = {
                        if (imagePath != null) engine.sendMultimodalMessage(prompt, imagePath)
                        else engine.sendMessageStreaming(prompt)
                    },
                    includeOrb = true,
                    recorder = rec,
                    promptCharsBase = prompt.length + selectedHistory.selectedChars,
                )
                val status = when {
                    result.timeout == GenTimeout.FIRST_TOKEN -> CompletionStatus.TIMEOUT_FIRST_TOKEN
                    result.timeout == GenTimeout.INTER_TOKEN -> CompletionStatus.TIMEOUT_INTER_TOKEN
                    cleanReply(result.text).isBlank() -> CompletionStatus.BLANK
                    else -> CompletionStatus.SUCCESS
                }
                val errorCategory = when (status) {
                    CompletionStatus.TIMEOUT_FIRST_TOKEN, CompletionStatus.TIMEOUT_INTER_TOKEN -> ErrorCategory.TIMEOUT
                    else -> ErrorCategory.NONE
                }
                rec.finish(status, errorCategory)
                activeRecorder = null
                finishTurn(turnId, assistantId, prompt, result, isVoice)
            } catch (_: CancellationException) {
                // Cancelled by cancelResponse()/supersession — UI already handled there.
                recorder?.finish(CompletionStatus.CANCELLED, ErrorCategory.CANCELLED)
                activeRecorder = null
            } catch (e: Exception) {
                recorder?.finish(CompletionStatus.ERROR, ErrorCategory.GENERATION_FAILURE)
                activeRecorder = null
                failTurn(turnId, assistantId, e, isVoice)
            }
        }
    }

    fun sendSuggestion(text: String) {
        updateInput(text)
        sendMessage()
    }

    fun cancelMachineCommand() {
        // Capture + null the job first so this is safe even if invoked from within the job,
        // and so cleanup runs exactly once.
        val job = machineCommandJob
        machineCommandJob = null
        resetGenerationUiState(cancelNative = true)
        job?.cancel()
        _brewState.value = BrewState()
    }

    suspend fun executeMachineCommand(command: MachineCommand, sessionId: String? = null) {
        if (sessionId != null) {
            currentSessionId = sessionId
        } else if (currentSessionId == null) {
            prepareNewSession()
        }
        val displayName = command.displayName
        _brewState.value = BrewState(BrewStatus.Preparing, displayName)
        val prompt = command.toMessage()

        machineCommandJob?.cancel()
        val turnId = beginTurn()
        val assistantId = UUID.randomUUID().toString()
        machineCommandJob = viewModelScope.launch {
            try {
                ensureSessionInStore()
                if (!_uiState.value.isEngineReady) {
                    loadActiveModelInternal()
                }
                _brewState.value = BrewState(BrewStatus.Brewing, displayName)
                val priorMessages = _uiState.value.messages
                val history = ChatLogic.selectHistory(priorMessages).turns
                val memory = syncClient.prefetchQuick(prompt) ?: syncClient.offlineContext()

                _uiState.update {
                    it.copy(
                        isLoading = true,
                        messages = it.messages +
                            UiMessage("user", prompt) +
                            UiMessage(id = assistantId, role = "assistant", content = "", isStreaming = true),
                        error = null,
                    )
                }

                val result = if (engine.isLoaded) {
                    runGeneration(
                        turnId = turnId,
                        assistantId = assistantId,
                        startConversation = { engine.startConversation(memory, history) },
                        makeFlow = { engine.sendMessageStreaming(prompt) },
                        includeOrb = false,
                    )
                } else {
                    GenResult("CoffeeAI is starting up. Your command has been queued.", GenTimeout.NONE, -1L, 0)
                }

                if (isActiveTurn(turnId)) {
                    val clean = cleanReply(result.text).trim()
                    if (clean.isNotBlank()) {
                        updateAssistantMessage(assistantId) { it.copy(content = clean, isStreaming = false) }
                        viewModelScope.launch { syncClient.syncTurn(prompt, clean) }
                    } else {
                        removeMessage(assistantId)
                    }
                    _uiState.update { it.copy(isLoading = false, orbState = OrbState.Idle) }
                    invalidateTurns()
                    persistCurrentSession()
                }
                _brewState.value = BrewState(BrewStatus.Ready, displayName)
            } catch (_: CancellationException) {
                _brewState.value = BrewState()
            } catch (e: Exception) {
                failTurn(turnId, assistantId, e, isVoice = false)
                _brewState.value = BrewState(
                    BrewStatus.Error,
                    displayName,
                    e.message ?: "Command failed",
                )
            }
        }
        machineCommandJob?.join()
    }

    fun clearChat() {
        viewModelScope.launch { clearChatInternal() }
    }

    private suspend fun clearChatInternal() {
        // Stop any in-flight text/voice/machine generation before wiping the list.
        val sJob = sendJob
        val mJob = machineCommandJob
        sendJob = null
        machineCommandJob = null
        resetGenerationUiState(cancelNative = true, clearError = true)
        sJob?.cancel()
        mJob?.cancel()
        _brewState.value = BrewState()
        _uiState.update { it.copy(messages = emptyList()) }
        currentSessionId?.let { sessionId ->
            if (!sessionStore.hasMessages(sessionId)) {
                sessionStore.deleteSession(sessionId)
            } else {
                sessionStore.saveMessages(sessionId, emptyList())
            }
        }
        if (engine.isLoaded) {
            engine.startConversation(syncClient.offlineContext())
        }
    }

    override fun onCleared() {
        invalidateTurns()
        engine.cancelGeneration()
        sendJob?.cancel()
        machineCommandJob?.cancel()
        viewModelScope.launch { engine.close() }
        super.onCleared()
    }
}
