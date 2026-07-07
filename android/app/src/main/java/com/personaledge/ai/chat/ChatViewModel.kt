package com.personaledge.ai.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personaledge.ai.EdgeAiApplication
import com.personaledge.ai.coffee.BrewState
import com.personaledge.ai.coffee.BrewStatus
import com.personaledge.ai.coffee.MachineCommand
import com.personaledge.ai.inference.ChatTurn
import com.personaledge.ai.inference.InferenceBackend
import com.personaledge.ai.inference.MemoryContext
import com.personaledge.ai.inference.LiteRTEngine
import com.personaledge.ai.models.ModelCatalog
import com.personaledge.ai.models.ModelEntry
import com.personaledge.ai.ui.components.OrbState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

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

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EdgeAiApplication
    private val modelRepository = app.modelRepository
    private val syncClient = app.syncClient
    private val sessionStore = app.chatSessionStore
    private val engine = LiteRTEngine(application)

    private var currentSessionId: String? = null
    private var sendJob: Job? = null
    private var machineCommandJob: Job? = null
    private var voiceSessionActive = false

    private companion object {
        const val STREAM_UI_THROTTLE_MS = 80L
    }

    private fun cleanReply(text: String): String =
        text
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace("*", "")
            .replace("`", "")
            .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*[-•]\\s+"), "")

    private fun appendStreamingToken(
        streamingIndex: Int,
        responseBuilder: StringBuilder,
        token: String,
        lastUiUpdateMs: Long,
        includeOrb: Boolean = true,
    ): Long {
        responseBuilder.append(token)
        val now = System.currentTimeMillis()
        if (now - lastUiUpdateMs < STREAM_UI_THROTTLE_MS) {
            return lastUiUpdateMs
        }
        _uiState.update { state ->
            val updated = state.messages.toMutableList()
            if (streamingIndex < updated.size) {
                updated[streamingIndex] = updated[streamingIndex].copy(
                    content = cleanReply(responseBuilder.toString()),
                    isStreaming = true,
                )
            }
            state.copy(
                messages = updated,
                orbState = if (includeOrb) OrbState.Thinking else state.orbState,
            )
        }
        return now
    }

    private fun finalizeStreamingMessage(
        streamingIndex: Int,
        finalResponse: String,
        clearLoading: Boolean = true,
    ) {
        _uiState.update { state ->
            val updated = state.messages.toMutableList()
            if (streamingIndex < updated.size) {
                updated[streamingIndex] = updated[streamingIndex].copy(
                    content = cleanReply(finalResponse),
                    isStreaming = false,
                )
            }
            state.copy(
                messages = updated,
                isLoading = if (clearLoading) false else state.isLoading,
                orbState = if (clearLoading) OrbState.Idle else state.orbState,
            )
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

    fun loadSession(sessionId: String) {
        if (currentSessionId == sessionId && _uiState.value.messages.isNotEmpty()) return
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
            val history = messages.map { ChatTurn(it.role, it.content) }
            engine.startConversation(memory, history)
        }
    }

    private suspend fun persistCurrentSession() {
        val sessionId = currentSessionId ?: return
        val messages = _uiState.value.messages.filter { !it.isStreaming }
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
        // Reloading on every screen entry closed/reopened the engine and raced with the
        // voice session start, which left the 2nd Let's Talk stuck on "Thinking".
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
            if (!modelRepository.ensureOnDisk(primary)) {
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
        return try {
            val backend = engine.loadModelWithFallback(entry, modelRepository.getModelFile(entry), preferGpu = preferGpu)
            if (preferGpu && backend == InferenceBackend.CPU) {
                syncClient.disableGpu()
            }
            val memory = syncClient.offlineContext()
            engine.startConversation(memory)
            Result.success(memory)
        } catch (e: Exception) {
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

    fun cancelResponse() {
        sendJob?.cancel()
        sendJob = null
        _uiState.update { state ->
            val trimmed = state.messages.toMutableList()
            if (trimmed.lastOrNull()?.role == "assistant" &&
                trimmed.last().content.isBlank()
            ) {
                trimmed.removeAt(trimmed.lastIndex)
            }
            state.copy(
                isLoading = false,
                orbState = OrbState.Idle,
                error = null,
                messages = trimmed.map { msg ->
                    if (msg.isStreaming) msg.copy(isStreaming = false) else msg
                },
            )
        }
    }

    fun beginVoiceSession() {
        viewModelScope.launch {
            if (!_uiState.value.isEngineReady) {
                loadActiveModelInternal()
            }
            if (!_uiState.value.isEngineReady) return@launch
            val memory = syncClient.offlineContext()
            val history = _uiState.value.messages
                .filter { !it.isStreaming }
                .map { ChatTurn(it.role, it.content) }
            engine.startVoiceConversation(memory, history)
            voiceSessionActive = true
        }
    }

    fun endVoiceSession() {
        voiceSessionActive = false
    }

    fun sendVoiceMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank() || _uiState.value.isLoading) return

        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            try {
                ensureSessionInStore()
                if (!_uiState.value.isEngineReady) {
                    loadActiveModelInternal()
                    if (!_uiState.value.isEngineReady) {
                        val err = _uiState.value.error ?: "CoffeeAI is still starting up. Please wait a moment."
                        _uiState.update {
                            it.copy(
                                inputText = "",
                                messages = it.messages + UiMessage("user", text) +
                                    UiMessage("assistant", err),
                            )
                        }
                        return@launch
                    }
                }

                _uiState.update {
                    it.copy(
                        inputText = "",
                        isLoading = true,
                        orbState = OrbState.Thinking,
                        messages = it.messages + UiMessage("user", text),
                        error = null,
                    )
                }

                if (!voiceSessionActive || !engine.hasActiveConversation()) {
                    val memory = syncClient.offlineContext()
                    val history = _uiState.value.messages
                        .filter { !it.isStreaming }
                        .dropLast(1)
                        .map { ChatTurn(it.role, it.content) }
                    engine.startVoiceConversation(memory, history)
                    voiceSessionActive = true
                }

                val streamingIndex = _uiState.value.messages.size
                val assistantId = UUID.randomUUID().toString()
                _uiState.update {
                    it.copy(
                        messages = it.messages + UiMessage(
                            id = assistantId,
                            role = "assistant",
                            content = "",
                            isStreaming = true,
                        ),
                    )
                }

                val responseBuilder = StringBuilder()
                var lastUiUpdateMs = 0L
                engine.sendMessageStreaming(text).collect { token ->
                    lastUiUpdateMs = appendStreamingToken(
                        streamingIndex,
                        responseBuilder,
                        token,
                        lastUiUpdateMs,
                    )
                }

                finalizeStreamingMessage(streamingIndex, responseBuilder.toString())
                viewModelScope.launch { syncClient.syncTurn(text, responseBuilder.toString()) }
                persistCurrentSession()
            } catch (_: CancellationException) {
                // barge-in or stop
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        orbState = OrbState.Idle,
                        error = e.message ?: "Generation failed",
                    )
                }
            }
        }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        val imagePath = _uiState.value.pendingImagePath
        if ((text.isBlank() && imagePath == null) || _uiState.value.isLoading) return
        val prompt = text.ifBlank { "Describe this image." }

        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            try {
                ensureSessionInStore()
                if (!_uiState.value.isEngineReady) {
                    loadActiveModelInternal()
                    if (!_uiState.value.isEngineReady) {
                        val err = _uiState.value.error ?: "CoffeeAI is still starting up. Please wait a moment."
                        _uiState.update {
                            it.copy(
                                inputText = "",
                                messages = it.messages + UiMessage("user", prompt, imageUri = it.pendingImageUri) +
                                    UiMessage("assistant", err),
                                pendingImagePath = null,
                                pendingImageUri = null,
                            )
                        }
                        return@launch
                    }
                }

                _uiState.update {
                    it.copy(
                        inputText = "",
                        isLoading = true,
                        orbState = OrbState.Thinking,
                        messages = it.messages + UiMessage(
                            "user",
                            prompt,
                            imageUri = it.pendingImageUri,
                        ),
                        error = null,
                        pendingImagePath = null,
                        pendingImageUri = null,
                    )
                }
                val memory = syncClient.prefetchQuick(prompt) ?: syncClient.offlineContext()
                _uiState.update { it.copy(memoryConnected = memory.memoryChunks.isNotEmpty()) }
                val history = _uiState.value.messages
                    .filter { !it.isStreaming }
                    .dropLast(1)
                    .map { ChatTurn(it.role, it.content) }
                engine.startConversation(memory, history)

                val streamingIndex = _uiState.value.messages.size
                val assistantId = UUID.randomUUID().toString()
                _uiState.update {
                    it.copy(
                        messages = it.messages + UiMessage(
                            id = assistantId,
                            role = "assistant",
                            content = "",
                            isStreaming = true,
                        ),
                    )
                }

                val responseBuilder = StringBuilder()
                var lastUiUpdateMs = 0L
                val flow = if (imagePath != null) {
                    engine.sendMultimodalMessage(prompt, imagePath)
                } else {
                    engine.sendMessageStreaming(prompt)
                }
                flow.collect { token ->
                    lastUiUpdateMs = appendStreamingToken(
                        streamingIndex,
                        responseBuilder,
                        token,
                        lastUiUpdateMs,
                    )
                }

                finalizeStreamingMessage(streamingIndex, responseBuilder.toString())
                viewModelScope.launch { syncClient.syncTurn(prompt, responseBuilder.toString()) }
                persistCurrentSession()
            } catch (_: CancellationException) {
                // barge-in or stop — state already handled by cancelResponse()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        orbState = OrbState.Idle,
                        error = e.message ?: "Generation failed",
                    )
                }
            }
        }
    }

    fun sendSuggestion(text: String) {
        updateInput(text)
        sendMessage()
    }

    fun cancelMachineCommand() {
        machineCommandJob?.cancel()
        machineCommandJob = null
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
        machineCommandJob = viewModelScope.launch {
            try {
                ensureSessionInStore()
                if (!_uiState.value.isEngineReady) {
                    loadActiveModelInternal()
                }
                _brewState.value = BrewState(BrewStatus.Brewing, displayName)
                _uiState.update {
                    it.copy(
                        isLoading = true,
                        messages = it.messages + UiMessage("user", prompt),
                        error = null,
                    )
                }
                val memory = syncClient.prefetchQuick(prompt) ?: syncClient.offlineContext()
                val history = _uiState.value.messages
                    .filter { !it.isStreaming }
                    .dropLast(1)
                    .map { ChatTurn(it.role, it.content) }
                if (engine.isLoaded) {
                    engine.startConversation(memory, history)
                }

                val streamingIndex = _uiState.value.messages.size
                val assistantId = UUID.randomUUID().toString()
                _uiState.update {
                    it.copy(
                        messages = it.messages + UiMessage(
                            id = assistantId,
                            role = "assistant",
                            content = "",
                            isStreaming = true,
                        ),
                    )
                }

                val responseBuilder = StringBuilder()
                if (engine.isLoaded) {
                    var lastUiUpdateMs = 0L
                    engine.sendMessageStreaming(prompt).collect { token ->
                        lastUiUpdateMs = appendStreamingToken(
                            streamingIndex,
                            responseBuilder,
                            token,
                            lastUiUpdateMs,
                            includeOrb = false,
                        )
                    }
                } else {
                    responseBuilder.append("CoffeeAI is starting up. Your command has been queued.")
                }

                finalizeStreamingMessage(
                    streamingIndex,
                    responseBuilder.toString(),
                    clearLoading = true,
                )
                viewModelScope.launch { syncClient.syncTurn(prompt, responseBuilder.toString()) }
                persistCurrentSession()
                _brewState.value = BrewState(BrewStatus.Ready, displayName)
            } catch (_: CancellationException) {
                _brewState.value = BrewState()
            } catch (e: Exception) {
                _brewState.value = BrewState(
                    BrewStatus.Error,
                    displayName,
                    e.message ?: "Command failed",
                )
                _uiState.update { it.copy(isLoading = false) }
            }
        }
        machineCommandJob?.join()
    }

    fun clearChat() {
        viewModelScope.launch { clearChatInternal() }
    }

    private suspend fun clearChatInternal() {
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
        viewModelScope.launch { engine.close() }
        super.onCleared()
    }
}
