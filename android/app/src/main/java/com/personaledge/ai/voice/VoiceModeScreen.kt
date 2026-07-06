package com.personaledge.ai.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personaledge.ai.EdgeAiApplication
import com.personaledge.ai.chat.ChatViewModel
import com.personaledge.ai.ui.components.CoffeeAiMark
import com.personaledge.ai.ui.components.LetsTalkPulseOrb
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeBrownDark
import com.personaledge.ai.ui.theme.CoffeeCreamDeep
import com.personaledge.ai.ui.theme.CoffeeText
import com.personaledge.ai.ui.theme.Error
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VoiceModeScreen(
    onBack: () -> Unit,
    chatViewModel: ChatViewModel = viewModel(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val app = context.applicationContext as EdgeAiApplication
    val scope = rememberCoroutineScope()
    val stt = app.sttManager
    val tts = app.ttsManager
    val chatState by chatViewModel.uiState.collectAsState()
    val isListening by stt.isListening.collectAsState()
    val partial by stt.partialText.collectAsState()
    val final by stt.finalText.collectAsState()
    val sttError by stt.error.collectAsState()
    val isModelReady by stt.isModelReady.collectAsState()
    val isModelLoading by stt.isModelLoading.collectAsState()
    val isSpeaking by tts.isSpeaking.collectAsState()
    var awaitingReply by remember { mutableStateOf(false) }
    var lastSpoken by remember { mutableStateOf("") }
    var resumeListeningAfterTts by remember { mutableStateOf(false) }

    SideEffect {
        val window = (view.context as Activity).window
        window.statusBarColor = CoffeeBrownDark.toArgb()
        window.navigationBarColor = Color.White.toArgb()
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
    }

    fun exitVoice() {
        resumeListeningAfterTts = false
        stt.stopListening()
        tts.stop()
        onBack()
    }

    BackHandler { exitVoice() }

    val orbActive = isListening || isSpeaking || chatState.isLoading || awaitingReply

    val statusTitle = when {
        isModelLoading -> "Loading voice model…"
        !isModelReady -> "Voice model not ready"
        isSpeaking -> "Speaking…"
        chatState.isLoading || awaitingReply -> "Thinking…"
        isListening -> "Listening…"
        else -> "Ready to listen"
    }

    val statusSubtitle = when {
        isSpeaking -> "Tap pause to interrupt"
        chatState.isLoading || awaitingReply -> "Please wait"
        isListening -> "Speak now or tap to pause"
        else -> "Tap the mic to start"
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
            tts.stop()
        }
    }

    LaunchedEffect(isModelReady) {
        if (isModelReady && !isListening && !isSpeaking && !chatState.isLoading) {
            stt.startListening()
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

    LaunchedEffect(isListening, final, partial) {
        if (!isListening && !chatState.isLoading && !awaitingReply && !isSpeaking) {
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
            .background(CoffeeBrownDark)
            .statusBarsPadding(),
    ) {
        LetsTalkHeader(onBack = ::exitVoice)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Color.White)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            LetsTalkPulseOrb(
                active = orbActive,
                modifier = Modifier.clickable(enabled = isModelReady) {
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
                text = statusTitle,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = CoffeeText,
                modifier = Modifier.padding(top = 32.dp),
            )
            Text(
                text = statusSubtitle,
                fontSize = 15.sp,
                color = CoffeeText.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 8.dp),
            )

            val transcript = partial.ifBlank { final }
            if (transcript.isNotBlank()) {
                Text(
                    text = transcript,
                    fontSize = 16.sp,
                    color = CoffeeText.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp, start = 12.dp, end = 12.dp),
                )
            }

            sttError?.let {
                Text(
                    text = it,
                    color = Error,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VoiceControlButton(
                    icon = Icons.Default.Mic,
                    background = CoffeeBrown,
                    iconTint = Color.White,
                    size = 56.dp,
                    onClick = {
                        if (isModelReady && !chatState.isLoading) {
                            if (isSpeaking) {
                                resumeListeningAfterTts = false
                                tts.stop()
                            }
                            stt.startListening()
                        }
                    },
                )
                VoiceControlButton(
                    icon = Icons.Default.Pause,
                    background = Color(0xFFC47A45),
                    iconTint = Color.White,
                    size = 72.dp,
                    onClick = {
                        when {
                            isSpeaking -> {
                                resumeListeningAfterTts = false
                                tts.stop()
                            }
                            isListening -> stt.stopListening()
                            isModelReady && !chatState.isLoading -> stt.startListening()
                        }
                    },
                )
                VoiceControlButton(
                    icon = Icons.Default.Close,
                    background = CoffeeCreamDeep,
                    iconTint = CoffeeBrown,
                    size = 56.dp,
                    onClick = ::exitVoice,
                )
            }
        }
    }
}

@Composable
private fun LetsTalkHeader(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(CoffeeBrownDark)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }

        Text(
            text = "Let's Talk",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center),
        )

        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CoffeeAiMark(modifier = Modifier.size(22.dp), color = Color.White.copy(alpha = 0.9f))
            Text(
                text = "CoffeeAI",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun VoiceControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    background: Color,
    iconTint: Color,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(size * 0.38f),
        )
    }
}
