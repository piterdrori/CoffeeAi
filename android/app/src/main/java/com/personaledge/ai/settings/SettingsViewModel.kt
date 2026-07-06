package com.personaledge.ai.settings

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personaledge.ai.EdgeAiApplication
import com.personaledge.ai.sync.BackendConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val readRepliesAloud: Boolean = true,
    val appVersion: String = "",
    val feedbackMessage: String? = null,
    val isBusy: Boolean = false,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EdgeAiApplication
    private val syncClient = app.syncClient
    private val chatStore = app.chatSessionStore
    private val memoryDao = app.memoryDatabase.memoryCacheDao()

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(appVersion = readAppVersion()) }
        viewModelScope.launch {
            val config = syncClient.getBackendConfig()
            _uiState.update { it.copy(readRepliesAloud = config.autoTts) }
        }
    }

    fun setReadRepliesAloud(enabled: Boolean) {
        _uiState.update { it.copy(readRepliesAloud = enabled) }
        viewModelScope.launch {
            val current = syncClient.getBackendConfig()
            syncClient.saveBackendConfig(current.copy(autoTts = enabled))
        }
    }

    fun clearAllChats() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                chatStore.clearAllSessions()
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        feedbackMessage = "All chats removed.",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        feedbackMessage = "Could not clear chats. Try again.",
                    )
                }
            }
        }
    }

    fun forgetSavedMemories() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                memoryDao.clearMemories()
                memoryDao.clearPendingSyncs()
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        feedbackMessage = "CoffeeAI forgot your saved memories.",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        feedbackMessage = "Could not clear memories. Try again.",
                    )
                }
            }
        }
    }

    fun clearFeedback() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    private fun readAppVersion(): String {
        return try {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            info.versionName ?: "1.0"
        } catch (_: PackageManager.NameNotFoundException) {
            "1.0"
        }
    }
}
