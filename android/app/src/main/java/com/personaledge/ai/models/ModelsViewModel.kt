package com.personaledge.ai.models

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personaledge.ai.EdgeAiApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelsUiState(
    val models: List<ModelUiItem> = emptyList(),
    val hfToken: String = "",
    val wifiOnly: Boolean = false,
    val activeModelId: String? = null,
)

data class ModelUiItem(
    val entry: ModelEntry,
    val status: DownloadStatus,
    val downloadedBytes: Long,
    val progress: Float,
    val error: String? = null,
)

class ModelsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as EdgeAiApplication).modelRepository

    private val _uiState = MutableStateFlow(ModelsUiState())
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            repository.hfToken.collect { token ->
                _uiState.update { it.copy(hfToken = token.orEmpty()) }
            }
        }
        viewModelScope.launch {
            repository.activeModelId.collect { id ->
                _uiState.update { it.copy(activeModelId = id) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val items = ModelCatalog.models.map { entry ->
                val downloaded = repository.isDownloaded(entry)
                val bytes = repository.getDownloadedSize(entry)
                val partial = bytes > 0 && !downloaded
                ModelUiItem(
                    entry = entry,
                    status = when {
                        downloaded -> DownloadStatus.COMPLETE
                        partial -> DownloadStatus.FAILED
                        else -> DownloadStatus.NOT_DOWNLOADED
                    },
                    downloadedBytes = bytes,
                    progress = if (downloaded) 1f else repository.downloadProgress(entry),
                    error = if (partial) {
                        "Incomplete file (${bytes / 1_000_000} MB). Delete and re-download."
                    } else {
                        null
                    },
                )
            }
            _uiState.update { it.copy(models = items) }
        }
    }

    fun updateHfToken(token: String) {
        _uiState.update { it.copy(hfToken = token) }
        viewModelScope.launch { repository.setHfToken(token) }
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        _uiState.update { it.copy(wifiOnly = wifiOnly) }
    }

    fun downloadModel(modelId: String) {
        viewModelScope.launch {
            val entry = ModelCatalog.findById(modelId) ?: return@launch
            if (entry.isGated && _uiState.value.hfToken.isBlank() && repository.getHfToken().isNullOrBlank()) {
                _uiState.update { state ->
                    val updated = state.models.map { item ->
                        if (item.entry.id == modelId) {
                            item.copy(
                                status = DownloadStatus.FAILED,
                                error = "Add Hugging Face token in Download settings (Gemma 3 is gated).",
                            )
                        } else {
                            item
                        }
                    }
                    state.copy(models = updated)
                }
                return@launch
            }
            val token = _uiState.value.hfToken.ifBlank { repository.getHfToken() }
            ModelDownloader.enqueue(getApplication(), modelId, token, _uiState.value.wifiOnly)
            observeDownload(modelId)
        }
    }

    fun cancelDownload(modelId: String) {
        ModelDownloader.cancel(getApplication(), modelId)
        refresh()
    }

    fun setActiveModel(modelId: String) {
        viewModelScope.launch {
            val entry = ModelCatalog.findById(modelId) ?: return@launch
            if (!repository.isDownloaded(entry)) return@launch
            repository.setActiveModel(modelId)
        }
    }

    fun importModel(modelId: String, uri: Uri) {
        viewModelScope.launch {
            val entry = ModelCatalog.findById(modelId) ?: return@launch
            repository.importFromUri(entry, uri)
            refresh()
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            val entry = ModelCatalog.findById(modelId) ?: return@launch
            repository.deleteModel(entry)
            refresh()
        }
    }

    private fun observeDownload(modelId: String) {
        viewModelScope.launch {
            ModelDownloader.observeProgress(getApplication(), modelId).collect { workInfo ->
                val entry = ModelCatalog.findById(modelId) ?: return@collect
                if (workInfo == null) return@collect
                val progress = workInfo.progress
                val downloaded = progress.getLong(ModelDownloader.PROGRESS_BYTES, 0L)
                val total = progress.getLong(ModelDownloader.PROGRESS_TOTAL, entry.sizeBytes)
                val fraction = if (total > 0) downloaded.toFloat() / total else 0f
                val status = when (workInfo.state) {
                    androidx.work.WorkInfo.State.RUNNING -> DownloadStatus.DOWNLOADING
                    androidx.work.WorkInfo.State.SUCCEEDED -> DownloadStatus.COMPLETE
                    androidx.work.WorkInfo.State.FAILED -> DownloadStatus.FAILED
                    androidx.work.WorkInfo.State.ENQUEUED -> DownloadStatus.QUEUED
                    else -> DownloadStatus.NOT_DOWNLOADED
                }
                _uiState.update { state ->
                    val updated = state.models.map { item ->
                        if (item.entry.id == modelId) {
                            item.copy(
                                status = status,
                                downloadedBytes = downloaded,
                                progress = fraction.coerceIn(0f, 1f),
                                error = workInfo.outputData.getString("error"),
                            )
                        } else {
                            item
                        }
                    }
                    state.copy(models = updated)
                }
                if (workInfo.state.isFinished) {
                    if (workInfo.state == androidx.work.WorkInfo.State.SUCCEEDED) {
                        repository.setActiveModel(modelId)
                    }
                    refresh()
                }
            }
        }
    }
}
