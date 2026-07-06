package com.personaledge.ai.models

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personaledge.ai.ui.components.EdgeModelCard
import com.personaledge.ai.ui.components.EdgeSection
import com.personaledge.ai.ui.theme.Accent
import com.personaledge.ai.ui.theme.Surface
import com.personaledge.ai.ui.theme.TextPrimary

@Composable
fun ModelsSection(viewModel: ModelsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    var downloadSettingsExpanded by remember { mutableStateOf(true) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EdgeSection(
            title = "Download settings",
            expanded = downloadSettingsExpanded,
            onToggle = { downloadSettingsExpanded = !downloadSettingsExpanded },
        ) {
            Text(
                "Gemma 3 requires a free Hugging Face token. Create one at hf.co/settings/tokens, accept the Gemma license on the model page, then paste the token below.",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = com.personaledge.ai.ui.theme.TextMuted,
            )
            OutlinedTextField(
                value = state.hfToken,
                onValueChange = viewModel::updateHfToken,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Hugging Face token") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Surface,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("WiFi-only downloads", color = TextPrimary)
                Switch(checked = state.wifiOnly, onCheckedChange = viewModel::setWifiOnly)
            }
        }

        state.models.forEach { item ->
            ModelCardItem(
                item = item,
                isActive = item.entry.id == state.activeModelId,
                onDownload = { viewModel.downloadModel(item.entry.id) },
                onCancel = { viewModel.cancelDownload(item.entry.id) },
                onActivate = { viewModel.setActiveModel(item.entry.id) },
                onImport = { uri -> viewModel.importModel(item.entry.id, uri) },
                onDelete = { viewModel.deleteModel(item.entry.id) },
            )
        }
    }
}

@Composable
private fun ModelCardItem(
    item: ModelUiItem,
    isActive: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onActivate: () -> Unit,
    onImport: (Uri) -> Unit,
    onDelete: () -> Unit,
) {
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onImport) }

    EdgeModelCard(
        name = item.entry.displayName,
        description = item.entry.description,
        sizeLabel = "${item.entry.sizeBytes / 1_000_000} MB · ${item.entry.minRamGb} GB RAM",
        status = item.status,
        progress = item.progress,
        isActive = isActive,
        error = item.error,
        onDownload = onDownload,
        onCancel = onCancel,
        onActivate = onActivate,
        onImport = { importLauncher.launch(arrayOf("*/*")) },
        onDelete = onDelete,
    )
}
