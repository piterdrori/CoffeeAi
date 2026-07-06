package com.personaledge.ai.navigation

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personaledge.ai.chat.ChatScreen
import com.personaledge.ai.chat.ChatViewModel
import com.personaledge.ai.home.ChatsSearchScreen
import com.personaledge.ai.home.FavoritesScreen
import com.personaledge.ai.home.HomeScreen
import com.personaledge.ai.home.ProfileScreen
import com.personaledge.ai.onboarding.OnboardingStore
import com.personaledge.ai.ui.components.CoffeeBottomNav
import com.personaledge.ai.ui.components.CoffeeNavTab
import com.personaledge.ai.ui.theme.CoffeeBrownDark
import com.personaledge.ai.ui.theme.CoffeeCream
import com.personaledge.ai.voice.VoiceModeScreen
import com.personaledge.ai.welcome.WelcomeScreen
import kotlinx.coroutines.launch

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onboardingStore = remember { OnboardingStore(context) }

    var showWelcome by remember { mutableStateOf<Boolean?>(null) }
    var selectedTab by remember { mutableStateOf(CoffeeNavTab.Home) }
    var activeChatSessionId by remember { mutableStateOf<String?>(null) }
    var showVoice by remember { mutableStateOf(false) }
    var returnToChatAfterVoice by remember { mutableStateOf(false) }
    var openProfileSettings by remember { mutableStateOf(false) }
    val chatViewModel: ChatViewModel = viewModel()

    LaunchedEffect(Unit) {
        showWelcome = !onboardingStore.isWelcomeComplete()
    }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let { chatViewModel.attachImage(it, context) }
    }

    when (showWelcome) {
        null -> Box(Modifier.fillMaxSize())
        true -> WelcomeScreen(
            onGetStarted = {
                scope.launch {
                    onboardingStore.markWelcomeComplete()
                    showWelcome = false
                }
            },
        )
        false -> MainAppContent(
            showVoice = showVoice,
            onShowVoice = {
                returnToChatAfterVoice = true
                showVoice = true
            },
            onHideVoice = { showVoice = false },
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            activeChatSessionId = activeChatSessionId,
            onOpenChat = { sessionId ->
                chatViewModel.loadSession(sessionId)
                activeChatSessionId = sessionId
            },
            onNewChat = {
                scope.launch {
                    val id = chatViewModel.prepareNewSession()
                    activeChatSessionId = id
                }
            },
            onLetsTalk = {
                scope.launch {
                    returnToChatAfterVoice = false
                    val id = chatViewModel.prepareNewSession()
                    activeChatSessionId = id
                    showVoice = true
                }
            },
            onQuickAction = { prompt ->
                scope.launch {
                    val id = chatViewModel.prepareNewSession()
                    activeChatSessionId = id
                    chatViewModel.sendSuggestion(prompt)
                }
            },
            onCloseChat = {
                chatViewModel.persistSession()
                activeChatSessionId = null
                showVoice = false
            },
            onExitVoice = {
                showVoice = false
                chatViewModel.persistSession()
                if (!returnToChatAfterVoice) {
                    activeChatSessionId = null
                }
                returnToChatAfterVoice = false
            },
            chatViewModel = chatViewModel,
            onAttachImage = { pickImage.launch("image/*") },
            openProfileSettings = openProfileSettings,
            onProfileSettingsConsumed = { openProfileSettings = false },
            onOpenProfileSettings = {
                selectedTab = CoffeeNavTab.Profile
                openProfileSettings = true
            },
        )
    }
}

@Composable
private fun MainAppContent(
    showVoice: Boolean,
    onShowVoice: () -> Unit,
    onHideVoice: () -> Unit,
    selectedTab: CoffeeNavTab,
    onTabSelected: (CoffeeNavTab) -> Unit,
    activeChatSessionId: String?,
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onLetsTalk: () -> Unit,
    onQuickAction: (String) -> Unit,
    onCloseChat: () -> Unit,
    onExitVoice: () -> Unit,
    chatViewModel: ChatViewModel,
    onAttachImage: () -> Unit,
    openProfileSettings: Boolean,
    onProfileSettingsConsumed: () -> Unit,
    onOpenProfileSettings: () -> Unit,
) {
    val canPopBack = showVoice ||
        activeChatSessionId != null ||
        selectedTab != CoffeeNavTab.Home

    BackHandler(enabled = canPopBack) {
        when {
            showVoice -> onExitVoice()
            activeChatSessionId != null -> onCloseChat()
            selectedTab != CoffeeNavTab.Home -> onTabSelected(CoffeeNavTab.Home)
        }
    }

    val view = LocalView.current
    SideEffect {
        val window = (view.context as Activity).window
        val onVoice = showVoice && activeChatSessionId != null
        window.statusBarColor = if (onVoice) CoffeeBrownDark.toArgb() else CoffeeCream.toArgb()
        window.navigationBarColor = Color.White.toArgb()
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !onVoice
    }

    if (showVoice && activeChatSessionId != null) {
        VoiceModeScreen(
            onBack = onExitVoice,
            chatViewModel = chatViewModel,
        )
        return
    }

    if (activeChatSessionId != null) {
        ChatScreen(
            modifier = Modifier.fillMaxSize(),
            onVoiceMode = onShowVoice,
            onAttachImage = onAttachImage,
            onBack = onCloseChat,
            viewModel = chatViewModel,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CoffeeCream),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                CoffeeNavTab.Home -> HomeScreen(
                    onLetsChat = onNewChat,
                    onLetsTalk = onLetsTalk,
                    onQuickAction = onQuickAction,
                    onOpenSettings = onOpenProfileSettings,
                    modifier = Modifier.fillMaxSize(),
                )
                CoffeeNavTab.Chats -> ChatsSearchScreen(
                    onOpenChat = onOpenChat,
                    modifier = Modifier.fillMaxSize(),
                )
                CoffeeNavTab.Favorites -> FavoritesScreen(
                    onOpenChat = onOpenChat,
                    onQuickAction = onQuickAction,
                    onLetsTalk = onLetsTalk,
                    modifier = Modifier.fillMaxSize(),
                )
                CoffeeNavTab.Profile -> ProfileScreen(
                    openAppSettings = openProfileSettings,
                    onAppSettingsConsumed = onProfileSettingsConsumed,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        CoffeeBottomNav(
            selected = selectedTab,
            onSelect = onTabSelected,
        )
    }
}
