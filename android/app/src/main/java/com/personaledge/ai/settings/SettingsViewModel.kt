package com.personaledge.ai.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personaledge.ai.EdgeAiApplication
import com.personaledge.ai.inference.InferenceBackend
import com.personaledge.ai.sync.BackendConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class SettingsUiState(
    val localUrl: String = "http://192.168.1.100:8080",
    val cloudUrl: String = "",
    val apiKey: String = "",
    val useGpu: Boolean = true,
    val autoTts: Boolean = true,
    val message: String? = null,
    val memoryConnected: Boolean = false,
    val memoryCount: Int = 0,
    val lastSyncedLabel: String? = null,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EdgeAiApplication
    private val syncClient = app.syncClient
    private val dao = app.memoryDatabase.memoryCacheDao()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val config = syncClient.getBackendConfig()
            _uiState.update {
                it.copy(
                    localUrl = config.localUrl,
                    cloudUrl = config.cloudUrl,
                    apiKey = config.apiKey,
                    useGpu = config.useGpu,
                    autoTts = config.autoTts,
                )
            }
            refreshMemoryStats()
            checkBackendHealth(config)
        }
    }

    fun updateLocalUrl(url: String) = _uiState.update { it.copy(localUrl = url) }
    fun updateCloudUrl(url: String) = _uiState.update { it.copy(cloudUrl = url) }
    fun updateApiKey(key: String) = _uiState.update { it.copy(apiKey = key) }
    fun setUseGpu(useGpu: Boolean) = _uiState.update { it.copy(useGpu = useGpu) }
    fun setAutoTts(auto: Boolean) = _uiState.update { it.copy(autoTts = auto) }

    fun save() {
        viewModelScope.launch {
            val config = BackendConfig(
                localUrl = _uiState.value.localUrl,
                cloudUrl = _uiState.value.cloudUrl,
                apiKey = _uiState.value.apiKey,
                useGpu = _uiState.value.useGpu,
                autoTts = _uiState.value.autoTts,
            )
            syncClient.saveBackendConfig(config)
            checkBackendHealth(config)
            _uiState.update { it.copy(message = "Settings saved") }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            try {
                syncClient.pullFullSync()
                syncClient.pushPendingTurns()
                refreshMemoryStats()
                _uiState.update { it.copy(message = "Sync complete", memoryConnected = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(message = e.message ?: "Sync failed", memoryConnected = false) }
            }
        }
    }

    private suspend fun refreshMemoryStats() {
        val memories = dao.getRecentMemories(500)
        val config = dao.getConfig()
        val label = config?.lastSyncedAt?.let {
            val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
            "Last synced ${fmt.format(Date(it))}"
        }
        _uiState.update {
            it.copy(memoryCount = memories.size, lastSyncedLabel = label)
        }
    }

    private suspend fun checkBackendHealth(config: BackendConfig) {
        val connected = try {
            val url = config.localUrl.trimEnd('/') + "/health"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
        _uiState.update { it.copy(memoryConnected = connected) }
    }

    fun inferenceBackend(): InferenceBackend =
        if (_uiState.value.useGpu) InferenceBackend.GPU else InferenceBackend.CPU
}
