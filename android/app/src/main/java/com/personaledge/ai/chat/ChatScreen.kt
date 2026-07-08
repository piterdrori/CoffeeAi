package com.personaledge.ai.chat

import androidx.activity.compose.BackHandler
import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.personaledge.ai.EdgeAiApplication
import com.personaledge.ai.ui.components.CoffeeAiMark
import com.personaledge.ai.ui.components.CoffeeChatBubble
import com.personaledge.ai.ui.components.CoffeeChatComposer
import com.personaledge.ai.ui.components.CoffeeSwirlBackground
import com.personaledge.ai.ui.components.EdgeOrb
import com.personaledge.ai.ui.components.OrbState
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeCream
import com.personaledge.ai.ui.theme.CoffeeText
import com.personaledge.ai.ui.theme.Error
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

private const val GREETING = "Hi! I'm CoffeeAI. What would you like to explore today?"

@Composable
fun ChatScreen(
    onVoiceMode: () -> Unit,
    onAttachImage: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: ChatViewModel = viewModel(),
) {
    val context = LocalContext.current
    val app = context.applicationContext as EdgeAiApplication
    val tts = app.ttsManager
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // Manual per-reply TTS. Only one reply plays at a time; a fresh speechId protects against stale
    // completion callbacks (e.g. after switching replies or leaving the screen).
    val speechSeq = remember { AtomicLong(0L) }
    var activeSpeechId by remember { mutableStateOf(0L) }
    var activeTtsMessageId by remember { mutableStateOf<String?>(null) }

    fun stopManualTts() {
        activeSpeechId = 0L
        activeTtsMessageId = null
        tts.stop()
    }

    fun onReplyTtsClick(message: UiMessage) {
        when (ChatTtsLogic.resolveTap(message.id, activeTtsMessageId)) {
            ChatTtsLogic.Tap.STOP -> stopManualTts()
            ChatTtsLogic.Tap.START -> {
                activeSpeechId = 0L // invalidate the prior reply's speech first
                tts.stop()
                val sid = speechSeq.incrementAndGet()
                activeSpeechId = sid
                activeTtsMessageId = message.id
                tts.speak(message.content, sid)
            }
        }
    }

    onBack?.let { back ->
        BackHandler { back() }
    }

    SideEffect {
        val window = (view.context as Activity).window
        window.statusBarColor = CoffeeCream.toArgb()
        window.navigationBarColor = CoffeeCream.toArgb()
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
    }

    DisposableEffect(Unit) {
        // Chat uses manual per-reply TTS only — never auto-read. Reject stale completions by id.
        tts.onSpeakComplete = { completedSpeechId ->
            scope.launch(Dispatchers.Main) {
                if (ChatTtsLogic.isStaleCallback(completedSpeechId, activeSpeechId)) return@launch
                activeTtsMessageId = null
                activeSpeechId = 0L
            }
        }
        onDispose {
            tts.onSpeakComplete = null
            activeSpeechId = 0L
            activeTtsMessageId = null
            tts.stop()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadActiveModel()
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        CoffeeSwirlBackground(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            LetsChatHeader(onBack = onBack)

            if (!state.isEngineReady && state.error != null) {
                Text(
                    text = state.error ?: "",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color = Error,
                    fontSize = 13.sp,
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.messages.isEmpty() && !state.isLoading) {
                    item(key = "greeting") {
                        CoffeeChatBubble(
                            role = "assistant",
                            content = GREETING,
                        )
                    }
                }

                items(state.messages, key = { it.id }) { message ->
                    val showTts = ChatTtsLogic.showButton(message.role, message.content, message.isStreaming)
                    CoffeeChatBubble(
                        role = message.role,
                        content = message.content,
                        isStreaming = message.isStreaming,
                        imageUri = message.imageUri,
                        showTtsButton = showTts,
                        isTtsPlaying = ChatTtsLogic.isPlaying(message.id, activeTtsMessageId),
                        onTtsClick = if (showTts) {
                            { onReplyTtsClick(message) }
                        } else {
                            null
                        },
                    )
                }

                if ((state.isLoading || state.orbState == OrbState.Thinking) &&
                    state.messages.lastOrNull()?.isStreaming != true
                ) {
                    item(key = "thinking") {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            EdgeOrb(state = OrbState.Thinking, size = 28.dp)
                            Text(
                                text = "Thinking…",
                                color = CoffeeText.copy(alpha = 0.6f),
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }

            state.pendingImageUri?.let { uri ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                    )
                    IconButton(onClick = { viewModel.clearPendingImage() }) {
                        Text("✕", color = CoffeeText.copy(alpha = 0.5f))
                    }
                }
            }

            CoffeeChatComposer(
                text = state.inputText,
                onTextChange = viewModel::updateInput,
                onSend = { viewModel.sendMessage() },
                onLetsTalk = onVoiceMode,
                enabled = !state.isLoading,
                canSend = (state.inputText.isNotBlank() || state.pendingImagePath != null) && !state.isLoading,
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding(),
            )
        }
    }
}

@Composable
private fun LetsChatHeader(
    onBack: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = CoffeeText,
                )
            }
        } else {
            Box(modifier = Modifier.size(48.dp))
        }

        Text(
            text = "Let's Chat",
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            textAlign = TextAlign.Center,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = CoffeeText,
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(end = 12.dp),
        ) {
            CoffeeAiMark(modifier = Modifier.size(28.dp), color = CoffeeBrown)
            Text(
                text = "CoffeeAI",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = CoffeeBrown,
            )
        }
    }
}
