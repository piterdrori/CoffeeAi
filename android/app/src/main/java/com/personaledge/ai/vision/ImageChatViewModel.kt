package com.personaledge.ai.vision

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personaledge.ai.EdgeAiApplication
import com.personaledge.ai.chat.UiMessage
import com.personaledge.ai.inference.InferenceBackend
import com.personaledge.ai.inference.LiteRTEngine
import com.personaledge.ai.models.ModelCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImageChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val isLoading: Boolean = false,
    val isE2BAvailable: Boolean = false,
    val error: String? = null,
)

class ImageChatViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EdgeAiApplication
    private val modelRepository = app.modelRepository
    private val syncClient = app.syncClient
    private val engine = LiteRTEngine(application)

    private val _uiState = MutableStateFlow(ImageChatUiState())
    val uiState: StateFlow<ImageChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val entry = ModelCatalog.findById(ModelCatalog.GEMMA4_E2B_ID)
            val available = entry != null && modelRepository.isDownloaded(entry)
            _uiState.update { it.copy(isE2BAvailable = available) }
            if (available && entry != null) {
                engine.loadModel(entry, modelRepository.getModelFile(entry), InferenceBackend.GPU)
            }
        }
    }

    fun analyzeImage(imagePath: String, prompt: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    messages = it.messages + UiMessage("user", prompt),
                )
            }
            try {
                val memory = syncClient.prefetch(prompt)
                engine.startConversation(memory)
                val responseBuilder = StringBuilder()
                engine.sendMultimodalMessage(prompt, imagePath).collect { token ->
                    responseBuilder.append(token)
                }
                val response = responseBuilder.toString()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        messages = it.messages + UiMessage("assistant", response),
                    )
                }
                syncClient.syncTurn(prompt, response)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Image analysis failed")
                }
            }
        }
    }

    override fun onCleared() {
        viewModelScope.launch { engine.close() }
        super.onCleared()
    }
}
