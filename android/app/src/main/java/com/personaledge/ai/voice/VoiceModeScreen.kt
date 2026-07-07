package com.personaledge.ai.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.personaledge.ai.ui.theme.CoffeeText
import com.personaledge.ai.ui.theme.Error
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class VoiceTalkPhase {
    Inactive,
    Listening,
    Thinking,
    Speaking,
}

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
    val partial by stt.partialText.collectAsState()
    val sttError by stt.error.collectAsState()
    val isModelReady by stt.isModelReady.collectAsState()
    val isModelLoading by stt.isModelLoading.collectAsState()
    val sessionActive by stt.sessionActive.collectAsState()
    val isSpeaking by tts.isSpeaking.collectAsState()
    val isTranscribing by stt.isTranscribing.collectAsState()
    val transcribeSeconds by stt.transcribeSeconds.collectAsState()
    val micLevel by stt.micLevel.collectAsState()
    val micConnected by stt.micConnected.collectAsState()
    val micInputSilent by stt.micInputSilent.collectAsState()

    var phase by remember { mutableStateOf(VoiceTalkPhase.Inactive) }
    var spokenChars by remember { mutableIntStateOf(0) }
    var liveTranscript by remember { mutableStateOf("") }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val micReadyForStart = hasMicPermission &&
        (SherpaVoiceConfig.isPhysicalDevice || micConnected || SherpaVoiceConfig.useOnlineSttOnEmulator)

    fun resumeListening() {
        phase = VoiceTalkPhase.Listening
        spokenChars = 0
        stt.setAcceptingUtterance(true)
        stt.setBargeInEnabled(enabled = false)
        liveTranscript = ""
        stt.clearText()
    }

    SideEffect {
        val window = (view.context as Activity).window
        window.statusBarColor = CoffeeBrownDark.toArgb()
        window.navigationBarColor = Color.White.toArgb()
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
    }

    fun stopVoiceSession() {
        tts.stop()
        chatViewModel.cancelResponse()
        chatViewModel.endVoiceSession()
        stt.setBargeInEnabled(enabled = false)
        stt.stopSession()
        phase = VoiceTalkPhase.Inactive
        spokenChars = 0
        liveTranscript = ""
        stt.clearText()
        stt.startMicMonitor()
    }

    fun interruptAiAndListen() {
        // #region agent log
        VoiceDebugLog.log(
            hypothesisId = "E",
            location = "VoiceModeScreen.kt:interruptAiAndListen",
            message = "user interrupt",
            data = mapOf(
                "phase" to phase.name,
                "isSpeaking" to isSpeaking,
                "isLoading" to chatState.isLoading,
            ),
        )
        // #endregion
        tts.stop()
        chatViewModel.cancelResponse()
        spokenChars = 0
        stt.setTtsPlaybackActive(false)
        if (sessionActive) {
            resumeListening()
        }
    }

    fun exitVoice() {
        stopVoiceSession()
        onBack()
    }

    fun startVoiceSession() {
        if (!isModelReady) return
        phase = VoiceTalkPhase.Listening
        liveTranscript = ""
        stt.clearText()
        stt.setAcceptingUtterance(true)
        stt.onUtteranceComplete = { text ->
            scope.launch(Dispatchers.Main) {
                if (phase != VoiceTalkPhase.Listening) return@launch
                liveTranscript = text
                spokenChars = 0
                phase = VoiceTalkPhase.Thinking
                stt.setAcceptingUtterance(false)
                stt.setBargeInEnabled(enabled = true, speaking = false)
                chatViewModel.updateInput(text)
                chatViewModel.sendVoiceMessage()
            }
        }
        stt.onBargeIn = {
            scope.launch(Dispatchers.Main) {
                // #region agent log
                VoiceDebugLog.log(
                    hypothesisId = "E",
                    location = "VoiceModeScreen.kt:onBargeIn",
                    message = "barge-in callback",
                    data = mapOf("phase" to phase.name, "isSpeaking" to isSpeaking),
                )
                // #endregion
                if (phase == VoiceTalkPhase.Thinking || phase == VoiceTalkPhase.Speaking) {
                    tts.stop()
                    chatViewModel.cancelResponse()
                    resumeListening()
                }
            }
        }
        stt.onPartialText = { text ->
            scope.launch(Dispatchers.Main) {
                if (phase == VoiceTalkPhase.Listening) {
                    liveTranscript = text
                }
            }
        }
        stt.startSession()
        chatViewModel.beginVoiceSession()
    }

    BackHandler {
        if (sessionActive) stopVoiceSession() else exitVoice()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            stt.initModel()
            stt.startMicMonitor()
        }
    }

    DisposableEffect(Unit) {
        tts.onSpeakComplete = {
            scope.launch(Dispatchers.Main) {
                if (!sessionActive || phase != VoiceTalkPhase.Speaking) return@launch
                val last = chatState.messages.lastOrNull()
                val stillStreaming = chatState.isLoading || last?.isStreaming == true
                val more = last?.content?.let { content ->
                    VoiceSpeakPlanner.nextChunk(content, spokenChars, stillStreaming)
                }
                if (more != null) {
                    spokenChars = more.second
                    tts.speak(more.first)
                } else if (!stillStreaming) {
                    resumeListening()
                }
            }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            stt.initModel()
            stt.startMicMonitor()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        onDispose {
            tts.onSpeakComplete = null
            stt.onUtteranceComplete = null
            stt.onBargeIn = null
            stt.onPartialText = null
            stopVoiceSession()
            stt.stopMicMonitor()
        }
    }

    LaunchedEffect(Unit) {
        tts.autoReadReplies = app.syncClient.getBackendConfig().autoTts
        chatViewModel.loadActiveModel()
    }

    LaunchedEffect(phase, isSpeaking, sessionActive) {
        if (!sessionActive) return@LaunchedEffect
        // #region agent log
        VoiceDebugLog.log(
            hypothesisId = "A",
            location = "VoiceModeScreen.kt:phaseEffect",
            message = "phase/isSpeaking sync",
            data = mapOf(
                "phase" to phase.name,
                "isSpeaking" to isSpeaking,
                "sessionActive" to sessionActive,
                "isLoading" to chatState.isLoading,
            ),
        )
        // #endregion
        stt.setTtsPlaybackActive(isSpeaking)
        when (phase) {
            VoiceTalkPhase.Thinking ->
                stt.setBargeInEnabled(enabled = true, speaking = false)
            VoiceTalkPhase.Speaking ->
                stt.setBargeInEnabled(enabled = true, speaking = true)
            else -> Unit
        }
    }

    LaunchedEffect(chatState.messages, chatState.isLoading, chatState.error, sessionActive, phase) {
        if (!sessionActive) return@LaunchedEffect

        if (chatState.error != null && (phase == VoiceTalkPhase.Thinking || phase == VoiceTalkPhase.Speaking)) {
            resumeListening()
            return@LaunchedEffect
        }

        val last = chatState.messages.lastOrNull() ?: return@LaunchedEffect
        if (last.role != "assistant" || !tts.autoReadReplies) return@LaunchedEffect
        if (phase != VoiceTalkPhase.Thinking && phase != VoiceTalkPhase.Speaking) return@LaunchedEffect

        val stillStreaming = chatState.isLoading || last.isStreaming
        val planned = VoiceSpeakPlanner.nextChunk(last.content, spokenChars, stillStreaming) ?: run {
            if (!stillStreaming && phase == VoiceTalkPhase.Thinking && last.content.isBlank()) {
                resumeListening()
            }
            return@LaunchedEffect
        }

        if (phase == VoiceTalkPhase.Thinking) {
            phase = VoiceTalkPhase.Speaking
            stt.setAcceptingUtterance(false)
        }
        spokenChars = planned.second
        tts.speak(planned.first)
    }

    val orbActive = sessionActive && phase != VoiceTalkPhase.Inactive

    val statusTitle = when {
        isModelLoading -> "Loading voice model…"
        !isModelReady -> "Voice model not ready"
        !chatState.isEngineReady && !sessionActive -> "Loading chat model…"
        phase == VoiceTalkPhase.Speaking || isSpeaking -> "Speaking…"
        phase == VoiceTalkPhase.Thinking || chatState.isLoading -> "Thinking…"
        phase == VoiceTalkPhase.Listening && isTranscribing -> "Transcribing…"
        phase == VoiceTalkPhase.Listening -> "Listening…"
        else -> "Tap Start to talk"
    }

    val statusSubtitle = when {
        phase == VoiceTalkPhase.Speaking || isSpeaking ->
            "Tap Stop AI to interrupt — voice stays active"
        phase == VoiceTalkPhase.Thinking && !chatState.isEngineReady ->
            "Loading CoffeeAI model — first launch can take a minute"
        phase == VoiceTalkPhase.Thinking || chatState.isLoading ->
            "Speak anytime to interrupt"
        phase == VoiceTalkPhase.Listening && isTranscribing ->
            if (transcribeSeconds > 0) {
                "Whisper is processing (${transcribeSeconds}s)…"
            } else if (SherpaVoiceConfig.useOnlineSttOnEmulator) {
                "Online speech recognition (emulator — needs internet)"
            } else {
                "Whisper is processing your speech"
            }
        phase == VoiceTalkPhase.Listening ->
            "Pause 2–3 seconds when you're done"
        !chatState.isEngineReady && !sessionActive ->
            chatState.engineStatus
        sessionActive ->
            "Voice session active"
        else ->
            "Hands-free conversation until you tap Stop"
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
                modifier = Modifier.size(220.dp),
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
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
            )

            MicLevelIndicator(
                level = if (phase == VoiceTalkPhase.Listening || phase == VoiceTalkPhase.Inactive) {
                    micLevel
                } else {
                    0f
                },
                connected = micConnected,
                visible = phase == VoiceTalkPhase.Listening || phase == VoiceTalkPhase.Inactive,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, start = 32.dp, end = 32.dp),
            )

            if (SherpaVoiceConfig.isEmulator && !sessionActive) {
                Text(
                    text = if (SherpaVoiceConfig.useOnlineSttOnEmulator) {
                        "Emulator uses online speech recognition (needs internet). On a real phone, Whisper runs fully offline."
                    } else {
                        "Emulator audio: press Volume Up on the emulator, then in Windows Volume Mixer raise \"qemu-system-x86_64\" (Android Emulator)."
                    },
                    fontSize = 12.sp,
                    color = CoffeeText.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp),
                )
            }

            if (!hasMicPermission && !sessionActive) {
                Text(
                    text = "Microphone permission is required for voice mode.",
                    fontSize = 13.sp,
                    color = Error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp, start = 16.dp, end = 16.dp),
                )
            } else if (SherpaVoiceConfig.isEmulator && !micConnected && !sessionActive) {
                Text(
                    text = if (micInputSilent) {
                        "Emulator is sending silence (0%). Restart emulator with -allow-host-audio, then open ⋯ → Microphone → Host audio input."
                    } else {
                        "Speak into your mic — the bar should move above 5%. Emulator: ⋯ → Microphone → Host audio input."
                    },
                    fontSize = 13.sp,
                    color = Error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp, start = 16.dp, end = 16.dp),
                )
            } else if (hasMicPermission && !sessionActive) {
                Text(
                    text = if (SherpaVoiceConfig.isPhysicalDevice) {
                        "Tap Start, then speak — your phone mic and speaker are used offline."
                    } else {
                        "Microphone working — tap Start when ready"
                    },
                    fontSize = 13.sp,
                    color = CoffeeBrown,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp, start = 16.dp, end = 16.dp),
                )
            }

            val transcript = liveTranscript.ifBlank { partial }
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

            if (sessionActive) {
                val aiActive = phase == VoiceTalkPhase.Thinking ||
                    phase == VoiceTalkPhase.Speaking ||
                    isSpeaking ||
                    chatState.isLoading
                Button(
                    onClick = {
                        if (aiActive) interruptAiAndListen() else stopVoiceSession()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB84A3A),
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text(
                        text = if (aiActive) "Stop AI" else "End session",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            } else {
                Button(
                    onClick = ::startVoiceSession,
                    enabled = isModelReady && !isModelLoading &&
                        micReadyForStart &&
                        chatState.isEngineReady,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CoffeeBrown,
                        contentColor = Color.White,
                        disabledContainerColor = CoffeeBrown.copy(alpha = 0.4f),
                    ),
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Text(
                        text = "Start",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MicLevelIndicator(
    level: Float,
    connected: Boolean,
    visible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Microphone",
                fontSize = 13.sp,
                color = CoffeeText.copy(alpha = 0.7f),
            )
            Text(
                text = "${(level * 100).toInt()}%",
                fontSize = 13.sp,
                color = if (connected) CoffeeBrown else Error,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(CoffeeText.copy(alpha = 0.12f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(level.coerceIn(0.02f, 1f))
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (connected) CoffeeBrown else Error.copy(alpha = 0.5f)),
            )
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
