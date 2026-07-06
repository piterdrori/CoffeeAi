package com.personaledge.ai.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personaledge.ai.EdgeAiApplication
import com.personaledge.ai.chat.ChatViewModel
import com.personaledge.ai.ui.components.EdgeOrb
import com.personaledge.ai.ui.components.OrbState
import com.personaledge.ai.ui.theme.Accent
import com.personaledge.ai.ui.theme.Background
import com.personaledge.ai.ui.theme.Error
import com.personaledge.ai.ui.theme.SurfaceRaised
import com.personaledge.ai.ui.theme.TextMuted
import com.personaledge.ai.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VoiceModeScreen(
    onBack: () -> Unit,
    chatViewModel: ChatViewModel = viewModel(),
) {
    val context = LocalContext.current
    val app = context.applicationContext as EdgeAiApplication
    val scope = rememberCoroutineScope()
    val stt = remember { SttManager(context) }
    val tts = remember { TtsManager(context) }
    val chatState by chatViewModel.uiState.collectAsState()
    val isListening by stt.isListening.collectAsState()
    val partial by stt.partialText.collectAsState()
    val final by stt.finalText.collectAsState()
    val sttError by stt.error.collectAsState()
    val isModelReady by stt.isModelReady.collectAsState()
    val isModelLoading by stt.isModelLoading.collectAsState()
    val isSpeaking by tts.isSpeaking.collectAsState()
    val sttBackend by stt.backendLabel.collectAsState()
    var awaitingReply by remember { mutableStateOf(false) }
    var lastSpoken by remember { mutableStateOf("") }
    var resumeListeningAfterTts by remember { mutableStateOf(false) }

    fun exitVoice() {
        resumeListeningAfterTts = false
        stt.stopListening()
        tts.stop()
        onBack()
    }

    BackHandler { exitVoice() }

    val orbState = when {
        isSpeaking -> OrbState.Speaking
        chatState.isLoading || awaitingReply -> OrbState.Thinking
        isListening -> OrbState.Listening
        isModelLoading -> OrbState.Thinking
        else -> OrbState.Idle
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) stt.initModel()
    }

    DisposableEffect(Unit) {
        tts.onSpeakComplete = {
            if (resumeListeningAfterTts && isModelReady) {
                resumeListeningAfterTts = false
                scope.launch {
                    delay(450)
                    stt.startListening()
                }
            }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            stt.initModel()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        onDispose {
            tts.onSpeakComplete = null
            stt.stopListening()
            stt.shutdown()
            tts.shutdown()
        }
    }

    LaunchedEffect(Unit) {
        tts.autoReadReplies = app.syncClient.getBackendConfig().autoTts
    }

    LaunchedEffect(chatState.messages.lastOrNull()?.content, chatState.isLoading) {
        val last = chatState.messages.lastOrNull()
        if (!chatState.isLoading && awaitingReply) {
            awaitingReply = false
        }
        if (!chatState.isLoading && last?.role == "assistant" && last.content.isNotBlank() &&
            last.content != lastSpoken && tts.autoReadReplies
        ) {
            lastSpoken = last.content
            resumeListeningAfterTts = true
            tts.speak(last.content)
        }
    }

    LaunchedEffect(isListening) {
        if (!isListening && !chatState.isLoading && !awaitingReply) {
            val text = stt.capturedText()
            if (text.isNotBlank()) {
                awaitingReply = true
                chatViewModel.updateInput(text)
                chatViewModel.sendMessage()
                stt.clearText()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { exitVoice() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to chat", tint = TextPrimary)
            }
            TextButton(onClick = { exitVoice() }) {
                Text("Back to chat", color = Accent)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            EdgeOrb(
                state = orbState,
                modifier = Modifier.clickable(enabled = isModelReady && !chatState.isLoading) {
                    when {
                        isSpeaking -> {
                            resumeListeningAfterTts = false
                            tts.stop()
                            stt.startListening()
                        }
                        isListening -> stt.stopListening()
                        else -> stt.startListening()
                    }
                },
            )
            Text(
                text = when {
                    isModelLoading -> "Loading offline speech model…"
                    !isModelReady -> "Speech model not ready"
                    orbState == OrbState.Listening -> "Listening… tap orb when done"
                    orbState == OrbState.Thinking -> "Thinking…"
                    orbState == OrbState.Speaking -> "Speaking… tap to interrupt"
                    else -> "Tap orb and talk"
                },
                modifier = Modifier.padding(top = 24.dp),
                color = TextMuted,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = partial.ifBlank { final }.ifBlank { "Your words appear here" },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, start = 24.dp, end = 24.dp),
                textAlign = TextAlign.Center,
                color = TextPrimary,
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "Tap orb, speak, tap again when done",
                modifier = Modifier.padding(top = 12.dp),
                textAlign = TextAlign.Center,
                color = TextMuted,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
            sttBackend?.let {
                Text(
                    text = "Engine: $it",
                    modifier = Modifier.padding(top = 4.dp),
                    textAlign = TextAlign.Center,
                    color = Accent,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }
            sttError?.let {
                Text(it, color = Error, modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
            }
        }

        Button(
            onClick = { exitVoice() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SurfaceRaised,
                contentColor = TextPrimary,
            ),
        ) {
            Text("End voice chat")
        }
    }
}
