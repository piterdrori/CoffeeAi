package com.personaledge.ai.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personaledge.ai.chat.ChatViewModel
import com.personaledge.ai.chat.UiMessage
import com.personaledge.ai.ui.components.CoffeeChatBubble
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeCream
import com.personaledge.ai.ui.theme.CoffeeText

@Composable
fun HelpSupportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    profileViewModel: ProfileViewModel = viewModel(),
    chatViewModel: ChatViewModel = viewModel(),
) {
    val profileState by profileViewModel.uiState.collectAsState()
    val chatState by chatViewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var teamMessage by remember { mutableStateOf("") }
    var helpSessionReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!helpSessionReady) {
            chatViewModel.prepareNewSession()
            helpSessionReady = true
        }
    }

    ProfileFormTheme {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(CoffeeCream)
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = CoffeeBrown)
                }
                Text(
                    text = "Help & Support",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = CoffeeText,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(color = Color(0xFFE8DDD0))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.White,
                contentColor = CoffeeBrown,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = CoffeeBrown,
                    )
                },
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            text = "Ask CoffeeAI",
                            color = if (selectedTab == 0) CoffeeBrown else CoffeeText.copy(alpha = 0.65f),
                            fontWeight = if (selectedTab == 0) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            text = "Message Team",
                            color = if (selectedTab == 1) CoffeeBrown else CoffeeText.copy(alpha = 0.65f),
                            fontWeight = if (selectedTab == 1) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                )
            }

            when (selectedTab) {
                0 -> HelpAiChatTab(
                    inputText = chatState.inputText,
                    messages = chatState.messages,
                    isLoading = chatState.isLoading,
                    onSend = { chatViewModel.sendMessage() },
                    onInputChange = chatViewModel::updateInput,
                )
                1 -> TeamMessageTab(
                    profile = profileState.profile,
                    message = teamMessage,
                    onMessageChange = { teamMessage = it },
                    sending = profileState.supportSending,
                    feedback = profileState.supportMessage,
                    onSend = {
                        profileViewModel.sendTeamMessage(teamMessage)
                        teamMessage = ""
                    },
                    onDismissFeedback = profileViewModel::clearSupportMessage,
                )
            }
        }
    }
}

@Composable
private fun HelpAiChatTab(
    inputText: String,
    messages: List<UiMessage>,
    isLoading: Boolean,
    onSend: () -> Unit,
    onInputChange: (String) -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        text = "Ask CoffeeAI anything — brewing, the app, or your saved chats.",
                        color = CoffeeText.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            items(messages, key = { "${it.role}-${it.content.hashCode()}" }) { message ->
                CoffeeChatBubble(
                    role = message.role,
                    content = message.content,
                    isStreaming = message.isStreaming,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CoffeeProfileTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = "Ask for help…",
            )
            IconButton(
                onClick = onSend,
                enabled = inputText.isNotBlank() && !isLoading,
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = CoffeeBrown)
            }
        }
    }
}

@Composable
private fun TeamMessageTab(
    profile: UserProfile,
    message: String,
    onMessageChange: (String) -> Unit,
    sending: Boolean,
    feedback: String?,
    onSend: () -> Unit,
    onDismissFeedback: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Send a message to the CoffeeAI team. We read every note.",
            color = CoffeeText.copy(alpha = 0.65f),
            fontSize = 14.sp,
        )
        if (profile.displayName.isNotBlank()) {
            Text(
                text = "From: ${profile.displayName}${if (profile.email.isNotBlank()) " · ${profile.email}" else ""}",
                color = CoffeeBrown,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        ProfileLabeledField(label = "Your message") {
            CoffeeProfileTextField(
                value = message,
                onValueChange = onMessageChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                placeholder = "Describe a bug, idea, or question for our team…",
                singleLine = false,
                minLines = 5,
            )
        }
        feedback?.let {
            Text(
                text = it,
                color = if (it.startsWith("Message sent")) CoffeeBrown else CoffeeText.copy(alpha = 0.7f),
                fontSize = 13.sp,
            )
            LaunchedEffect(it) {
                kotlinx.coroutines.delay(4000)
                onDismissFeedback()
            }
        }
        Button(
            onClick = onSend,
            enabled = message.isNotBlank() && !sending,
            modifier = Modifier.fillMaxWidth(),
            colors = profilePrimaryButtonColors(),
        ) {
            Text(if (sending) "Sending…" else "Send to team")
        }
    }
}
