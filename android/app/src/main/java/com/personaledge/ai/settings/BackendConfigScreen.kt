package com.personaledge.ai.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackendConfigScreen(viewModel: SettingsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.localUrl,
                onValueChange = viewModel::updateLocalUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Local backend URL") },
            )
            OutlinedTextField(
                value = state.cloudUrl,
                onValueChange = viewModel::updateCloudUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Cloud mirror URL (optional)") },
            )
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::updateApiKey,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key") },
            )
            RowSetting("Use GPU backend", state.useGpu) { viewModel.setUseGpu(it) }
            RowSetting("Auto-read replies (TTS)", state.autoTts) { viewModel.setAutoTts(it) }
            Button(onClick = { viewModel.save() }, modifier = Modifier.fillMaxWidth()) {
                Text("Save settings")
            }
            Button(onClick = { viewModel.syncNow() }, modifier = Modifier.fillMaxWidth()) {
                Text("Sync memory now")
            }
            state.message?.let { Text(it) }
        }
    }
}

@Composable
private fun RowSetting(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
