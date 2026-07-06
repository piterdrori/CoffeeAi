package com.personaledge.ai.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personaledge.ai.models.ModelsSection
import com.personaledge.ai.ui.components.EdgeSection
import com.personaledge.ai.ui.theme.Accent
import com.personaledge.ai.ui.theme.Background
import com.personaledge.ai.ui.theme.Surface
import com.personaledge.ai.ui.theme.TextMuted
import com.personaledge.ai.ui.theme.TextPrimary

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var modelsExpanded by remember { mutableStateOf(true) }
    var memoryExpanded by remember { mutableStateOf(true) }
    var backendExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Settings",
            style = androidx.compose.material3.MaterialTheme.typography.displaySmall,
            color = TextPrimary,
        )

        EdgeSection(
            title = "Models",
            expanded = modelsExpanded,
            onToggle = { modelsExpanded = !modelsExpanded },
        ) {
            ModelsSection()
        }

        EdgeSection(
            title = "Memory",
            expanded = memoryExpanded,
            onToggle = { memoryExpanded = !memoryExpanded },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (state.memoryConnected) Accent else TextMuted),
                )
                Text(
                    text = if (state.memoryConnected) "Backend connected" else "Offline — chat still works",
                    modifier = Modifier.padding(start = 8.dp),
                    color = TextPrimary,
                )
            }
            state.lastSyncedLabel?.let {
                Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = "${state.memoryCount} memory chunks cached",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = {
                    val url = state.localUrl.trimEnd('/') + "/admin"
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
            ) {
                Text("Manage memory on PC")
            }
            Button(onClick = { viewModel.syncNow() }, modifier = Modifier.fillMaxWidth()) {
                Text("Sync now")
            }
        }

        EdgeSection(
            title = "Backend",
            expanded = backendExpanded,
            onToggle = { backendExpanded = !backendExpanded },
        ) {
            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Surface,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
            )
            OutlinedTextField(
                value = state.localUrl,
                onValueChange = viewModel::updateLocalUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Backend URL") },
                colors = fieldColors,
            )
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::updateApiKey,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key") },
                colors = fieldColors,
            )
            RowSetting("Use GPU", state.useGpu, viewModel::setUseGpu)
            RowSetting("Auto-read replies (TTS)", state.autoTts, viewModel::setAutoTts)
            Button(
                onClick = { viewModel.save() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
            ) {
                Text("Save")
            }
        }

        state.message?.let {
            Text(it, color = Accent, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RowSetting(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextPrimary)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
