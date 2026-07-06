package com.personaledge.ai.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personaledge.ai.EdgeAiApplication
import com.personaledge.ai.inference.ChatTurn
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
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

data class UiMessage(
    val role: String,
    val content: String,
    val isStreaming: Boolean = false,
    val imageUri: String? = null,
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
    private val engine = LiteRTEngine(application)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            modelRepository.activeModelId.distinctUntilChanged().collect {
                loadActiveModel()
            }
        }
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun loadActiveModel() {
        viewModelScope.launch { loadActiveModelInternal() }
    }

    private suspend fun loadActiveModelInternal() {
        _uiState.update { it.copy(error = null, engineStatus = "Loading…") }
        val preferGpu = syncClient.getBackendConfig().useGpu
        val activeId = modelRepository.activeModelId.first()
        val primary = activeId?.let { ModelCatalog.findById(it) }
            ?: ModelCatalog.models.firstOrNull { modelRepository.isDownloaded(it) }
            ?: ModelCatalog.defaultModel()

        if (!modelRepository.isDownloaded(primary)) {
            val partial = modelRepository.getDownloadedSize(primary)
            val msg = if (partial > 0) {
                "${primary.displayName} download incomplete. Delete and re-download in Settings."
            } else {
                "Download ${primary.displayName} in Settings → Models."
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
            it.id != primary.id && modelRepository.isDownloaded(it)
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
            engine.loadModelWithFallback(entry, modelRepository.getModelFile(entry), preferGpu = preferGpu)
            val memory = try {
                syncClient.prefetch("")
            } catch (_: Exception) {
                null
            }
            engine.startConversation(memory ?: MemoryContext())
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

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        val imagePath = _uiState.value.pendingImagePath
        if ((text.isBlank() && imagePath == null) || _uiState.value.isLoading) return
        val prompt = text.ifBlank { "Describe this image." }

        viewModelScope.launch {
            if (!_uiState.value.isEngineReady) {
                loadActiveModelInternal()
                if (!_uiState.value.isEngineReady) {
                    val err = _uiState.value.error ?: "Model not ready. Check Settings → Models."
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
            try {
                val memory = try {
                    syncClient.prefetch(prompt)
                } catch (_: Exception) {
                    null
                }
                _uiState.update { it.copy(memoryConnected = memory != null) }
                val history = _uiState.value.messages
                    .filter { !it.isStreaming }
                    .dropLast(1)
                    .map { ChatTurn(it.role, it.content) }
                engine.startConversation(memory ?: MemoryContext(), history)

                val streamingIndex = _uiState.value.messages.size
                _uiState.update {
                    it.copy(messages = it.messages + UiMessage("assistant", "", isStreaming = true))
                }

                val responseBuilder = StringBuilder()
                val flow = if (imagePath != null) {
                    engine.sendMultimodalMessage(prompt, imagePath)
                } else {
                    engine.sendMessageStreaming(prompt)
                }
                flow.collect { token ->
                    responseBuilder.append(token)
                    _uiState.update { state ->
                        val updated = state.messages.toMutableList()
                        if (streamingIndex < updated.size) {
                            updated[streamingIndex] = UiMessage(
                                "assistant",
                                responseBuilder.toString(),
                                isStreaming = true,
                            )
                        }
                        state.copy(messages = updated, orbState = OrbState.Thinking)
                    }
                }

                val finalResponse = responseBuilder.toString()
                _uiState.update { state ->
                    val updated = state.messages.toMutableList()
                    if (streamingIndex < updated.size) {
                        updated[streamingIndex] = UiMessage("assistant", finalResponse)
                    }
                    state.copy(messages = updated, isLoading = false, orbState = OrbState.Idle)
                }
                syncClient.syncTurn(prompt, finalResponse)
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

    fun clearChat() {
        _uiState.update { it.copy(messages = emptyList()) }
        viewModelScope.launch {
            if (engine.isLoaded) {
                val memory = try {
                    syncClient.prefetch("")
                } catch (_: Exception) {
                    null
                }
                engine.startConversation(memory ?: MemoryContext())
            }
        }
    }

    override fun onCleared() {
        viewModelScope.launch { engine.close() }
        super.onCleared()
    }
}
