package com.personaledge.ai.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import coil.compose.AsyncImage
import com.personaledge.ai.EdgeAiApplication
import com.personaledge.ai.voice.TtsManager
import com.personaledge.ai.ui.components.EdgeChip
import com.personaledge.ai.ui.components.EdgeComposer
import com.personaledge.ai.ui.components.EdgeMessage
import com.personaledge.ai.ui.components.EdgeOrb
import com.personaledge.ai.ui.components.OrbState
import com.personaledge.ai.ui.theme.Accent
import com.personaledge.ai.ui.theme.Background
import com.personaledge.ai.ui.theme.Error
import com.personaledge.ai.ui.theme.TextMuted
import com.personaledge.ai.ui.theme.TextPrimary

@Composable
fun ChatScreen(
    onVoiceMode: () -> Unit,
    onAttachImage: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = viewModel(),
) {
    val context = LocalContext.current
    val app = context.applicationContext as EdgeAiApplication
    val tts = remember { TtsManager(context) }
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var autoTts by remember { mutableStateOf(true) }
    var lastSpoken by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose { tts.shutdown() }
    }

    LaunchedEffect(Unit) {
        autoTts = app.syncClient.getBackendConfig().autoTts
    }

    LaunchedEffect(state.messages.lastOrNull()?.content, state.isLoading, autoTts) {
        val last = state.messages.lastOrNull()
        if (autoTts && !state.isLoading && last?.role == "assistant" && !last.isStreaming &&
            last.content.isNotBlank() && last.content != lastSpoken
        ) {
            lastSpoken = last.content
            tts.speak(last.content)
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .imePadding(),
    ) {
        ChatHeader(
            modelName = state.activeModelName,
            engineStatus = state.engineStatus,
            isEngineReady = state.isEngineReady,
            memoryConnected = state.memoryConnected,
            onClear = { viewModel.clearChat() },
        )

        if (!state.isEngineReady && state.error != null) {
            Text(
                text = state.error ?: "",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = Error,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
        }

        state.error?.takeIf { state.isEngineReady }?.let {
            Text(
                text = it,
                modifier = Modifier.padding(horizontal = 16.dp),
                color = Error,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (state.messages.isEmpty() && !state.isLoading) {
                ChatEmptyState(
                    onSuggestion = viewModel::sendSuggestion,
                    onVoiceMode = onVoiceMode,
                    isEngineReady = state.isEngineReady,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.messages) { message ->
                        EdgeMessage(
                            role = message.role,
                            content = message.content,
                            isStreaming = message.isStreaming,
                            imageUri = message.imageUri,
                            onSpeak = if (message.role == "assistant" && message.content.isNotBlank() && !message.isStreaming) {
                                { tts.speak(message.content) }
                            } else {
                                null
                            },
                        )
                    }
                    if (state.isLoading || state.orbState == OrbState.Thinking) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                EdgeOrb(state = OrbState.Thinking, size = 32.dp)
                                Text("Thinking…", color = TextMuted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        state.pendingImageUri?.let { uri ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.clearPendingImage() }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove image", tint = TextMuted)
                }
            }
        }

        EdgeComposer(
            text = state.inputText,
            onTextChange = viewModel::updateInput,
            onSend = { viewModel.sendMessage() },
            onAttach = onAttachImage,
            onVoice = onVoiceMode,
            enabled = !state.isLoading,
            canSend = (state.inputText.isNotBlank() || state.pendingImagePath != null) && !state.isLoading,
        )
    }
}

@Composable
private fun ChatHeader(
    modelName: String?,
    engineStatus: String,
    isEngineReady: Boolean,
    memoryConnected: Boolean,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isEngineReady -> Accent
                        memoryConnected -> TextMuted
                        else -> Error.copy(alpha = 0.8f)
                    },
                ),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                text = modelName ?: "No model selected",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            Text(
                text = engineStatus,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = if (isEngineReady) Accent else TextMuted,
            )
        }
        IconButton(onClick = onClear) {
            Icon(Icons.Default.Close, contentDescription = "Clear chat", tint = TextMuted)
        }
    }
}

@Composable
private fun ChatEmptyState(
    onSuggestion: (String) -> Unit,
    onVoiceMode: () -> Unit,
    isEngineReady: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row {
            Text(
                text = "Edge",
                style = androidx.compose.material3.MaterialTheme.typography.displaySmall,
                color = Accent,
            )
            Text(
                text = "AI",
                style = androidx.compose.material3.MaterialTheme.typography.displaySmall,
                color = TextPrimary,
            )
        }
        Text(
            text = "Your offline assistant",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        EdgeChip(label = "Ask anything", onClick = { onSuggestion("Hello, how can you help me today?") })
        EdgeChip(label = "Describe an image", onClick = { onSuggestion("Describe an image I attach.") })
        if (isEngineReady) {
            EdgeChip(label = "Voice chat", onClick = onVoiceMode)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
                Text(
                    text = "Or tap the mic below — replies are read aloud (TTS)",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        } else {
            Text(
                text = "Download a model in Settings to get started",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}
