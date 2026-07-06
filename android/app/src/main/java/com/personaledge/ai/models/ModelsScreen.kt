package com.personaledge.ai.models

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(viewModel: ModelsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Models") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.hfToken,
                    onValueChange = viewModel::updateHfToken,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Hugging Face token (for Gemma 3)") },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("WiFi-only downloads")
                    Switch(checked = state.wifiOnly, onCheckedChange = viewModel::setWifiOnly)
                }
            }
            items(state.models) { item ->
                ModelCard(
                    item = item,
                    isActive = item.entry.id == state.activeModelId,
                    onDownload = { viewModel.downloadModel(item.entry.id) },
                    onCancel = { viewModel.cancelDownload(item.entry.id) },
                    onActivate = { viewModel.setActiveModel(item.entry.id) },
                    onDelete = { viewModel.deleteModel(item.entry.id) },
                    onImport = { uri -> viewModel.importModel(item.entry.id, uri) },
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    item: ModelUiItem,
    isActive: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onImport: (Uri) -> Unit,
) {
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onImport) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.entry.displayName, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Text(item.entry.description)
            Text("Size: ${item.entry.sizeBytes / 1_000_000} MB · Min RAM: ${item.entry.minRamGb} GB")
            if (item.status == DownloadStatus.DOWNLOADING) {
                LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
            }
            item.error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (item.status) {
                    DownloadStatus.COMPLETE -> {
                        Button(onClick = onActivate, enabled = !isActive) {
                            Text(if (isActive) "Active" else "Use model")
                        }
                        Button(onClick = onDelete) { Text("Delete") }
                    }
                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                        Button(onClick = onCancel) { Text("Cancel") }
                    }
                    else -> {
                        Button(onClick = onDownload) { Text("Download") }
                        Button(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import") }
                    }
                }
            }
        }
    }
}
