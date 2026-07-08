package com.personaledge.ai.navigation



import android.app.Activity

import android.net.Uri

import androidx.activity.compose.BackHandler

import androidx.activity.compose.rememberLauncherForActivityResult

import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.background

import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.compose.material3.ModalBottomSheet

import androidx.compose.material3.rememberModalBottomSheetState

import androidx.compose.runtime.Composable

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

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.toArgb

import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.platform.LocalView

import androidx.core.view.WindowCompat

import androidx.lifecycle.viewmodel.compose.viewModel

import com.personaledge.ai.chat.ChatScreen

import com.personaledge.ai.chat.ChatViewModel

import com.personaledge.ai.coffee.CoffeeRecipeEntity

import com.personaledge.ai.coffee.QuickActionEntity

import com.personaledge.ai.coffee.SavedTopicEntity

import com.personaledge.ai.home.ChatsSearchScreen

import com.personaledge.ai.home.FavoritesScreen

import com.personaledge.ai.home.FavoritesViewModel

import com.personaledge.ai.home.HelpTopicsSheet

import com.personaledge.ai.home.HomeScreen

import com.personaledge.ai.home.ManageQuickActionsScreen

import com.personaledge.ai.home.ProfileScreen

import com.personaledge.ai.home.RecipeEditorScreen

import com.personaledge.ai.onboarding.OnboardingStore

import com.personaledge.ai.ui.components.BrewStatusSheet

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

    var showManageActions by remember { mutableStateOf(false) }

    var showRecipeEditor by remember { mutableStateOf(false) }

    var editingRecipe by remember { mutableStateOf<CoffeeRecipeEntity?>(null) }

    var showHelpTopics by remember { mutableStateOf(false) }

    val chatViewModel: ChatViewModel = viewModel()

    val favoritesViewModel: FavoritesViewModel = viewModel()



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

            showManageActions = showManageActions,

            onShowManageActions = { showManageActions = true },

            onHideManageActions = { showManageActions = false },

            showRecipeEditor = showRecipeEditor,

            editingRecipe = editingRecipe,

            onShowRecipeEditor = { recipe ->

                editingRecipe = recipe

                showRecipeEditor = true

            },

            onHideRecipeEditor = {

                showRecipeEditor = false

                editingRecipe = null

            },

            showHelpTopics = showHelpTopics,

            onShowHelpTopics = { showHelpTopics = true },

            onHideHelpTopics = { showHelpTopics = false },

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

            onQuickAction = { action ->

                scope.launch {

                    val app = context.applicationContext as com.personaledge.ai.EdgeAiApplication
                    val beans = app.profileStore.currentCoffeePreferences().machineContextBlock()
                    val id = chatViewModel.prepareNewSession()

                    val command = app.coffeeActionStore.toMachineCommand(action, beans)

                    chatViewModel.executeMachineCommand(command, sessionId = id)

                }

            },

            onBrewRecipe = { recipe ->

                scope.launch {

                    // Deterministic favorite execution — structured recipe → BrewPlan, no LLM prompt.
                    val id = chatViewModel.prepareNewSession()

                    chatViewModel.brewFavorite(recipe, sessionId = id)

                }

            },

            onHelpTopic = { prompt ->

                scope.launch {

                    val id = chatViewModel.prepareNewSession()

                    activeChatSessionId = id

                    chatViewModel.sendSuggestion(prompt)

                }

            },

            onSaveRecipe = { recipe ->
                favoritesViewModel.saveRecipe(recipe)
                showRecipeEditor = false
                editingRecipe = null
                selectedTab = CoffeeNavTab.Favorites
            },

            onToggleFavorite = { sessionId, favorite ->

                favoritesViewModel.setFavorite(sessionId, favorite)

            },

            onToggleTopicSaved = { topic, saved ->

                favoritesViewModel.toggleTopicSaved(topic, saved)

            },

            onCloseChat = {

                chatViewModel.persistSession()

                activeChatSessionId = null

                showVoice = false

                chatViewModel.cancelMachineCommand()

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

            favoritesViewModel = favoritesViewModel,

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



@OptIn(ExperimentalMaterial3Api::class)

@Composable

private fun MainAppContent(

    showVoice: Boolean,

    onShowVoice: () -> Unit,

    onHideVoice: () -> Unit,

    selectedTab: CoffeeNavTab,

    onTabSelected: (CoffeeNavTab) -> Unit,

    activeChatSessionId: String?,

    showManageActions: Boolean,

    onShowManageActions: () -> Unit,

    onHideManageActions: () -> Unit,

    showRecipeEditor: Boolean,

    editingRecipe: CoffeeRecipeEntity?,

    onShowRecipeEditor: (CoffeeRecipeEntity?) -> Unit,

    onHideRecipeEditor: () -> Unit,

    showHelpTopics: Boolean,

    onShowHelpTopics: () -> Unit,

    onHideHelpTopics: () -> Unit,

    onOpenChat: (String) -> Unit,

    onNewChat: () -> Unit,

    onLetsTalk: () -> Unit,

    onQuickAction: (QuickActionEntity) -> Unit,

    onBrewRecipe: (CoffeeRecipeEntity) -> Unit,

    onHelpTopic: (String) -> Unit,

    onSaveRecipe: (CoffeeRecipeEntity) -> Unit,

    onToggleFavorite: (String, Boolean) -> Unit,

    onToggleTopicSaved: (SavedTopicEntity, Boolean) -> Unit,

    onCloseChat: () -> Unit,

    onExitVoice: () -> Unit,

    chatViewModel: ChatViewModel,

    favoritesViewModel: FavoritesViewModel,

    onAttachImage: () -> Unit,

    openProfileSettings: Boolean,

    onProfileSettingsConsumed: () -> Unit,

    onOpenProfileSettings: () -> Unit,

) {

    val brewState by chatViewModel.brewState.collectAsState()

    val allTopics by favoritesViewModel.allTopics.collectAsState()

    val helpSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)



    val canPopBack = showVoice ||

        activeChatSessionId != null ||

        showManageActions ||

        showRecipeEditor ||

        selectedTab != CoffeeNavTab.Home



    BackHandler(enabled = canPopBack) {

        when {

            showVoice -> onExitVoice()

            activeChatSessionId != null -> onCloseChat()

            showRecipeEditor -> onHideRecipeEditor()

            showManageActions -> onHideManageActions()

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

        VoiceModeScreen(onBack = onExitVoice, chatViewModel = chatViewModel)

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



    if (showManageActions) {

        ManageQuickActionsScreen(onBack = onHideManageActions, modifier = Modifier.fillMaxSize())

        return

    }



    if (showRecipeEditor) {

        RecipeEditorScreen(

            existing = editingRecipe,

            onSave = onSaveRecipe,

            onBack = onHideRecipeEditor,

            modifier = Modifier.fillMaxSize(),

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

                    onBrewRecipe = onBrewRecipe,

                    onMachineAction = onQuickAction,

                    onOpenSettings = onOpenProfileSettings,

                    onOpenFavoriteBeverages = { onTabSelected(CoffeeNavTab.Favorites) },

                    onOpenManageMachine = onShowManageActions,

                    modifier = Modifier.fillMaxSize(),

                )

                CoffeeNavTab.Chats -> ChatsSearchScreen(

                    onOpenChat = onOpenChat,

                    onNewChat = onNewChat,

                    onToggleFavorite = onToggleFavorite,

                    modifier = Modifier.fillMaxSize(),

                )

                CoffeeNavTab.Favorites -> FavoritesScreen(

                    onOpenChat = onOpenChat,

                    onBrewRecipe = onBrewRecipe,

                    onHelpTopic = onHelpTopic,

                    onAddRecipe = { onShowRecipeEditor(null) },

                    onEditRecipe = { onShowRecipeEditor(it) },

                    onBrowseHelp = onShowHelpTopics,

                    modifier = Modifier.fillMaxSize(),

                    viewModel = favoritesViewModel,

                )

                CoffeeNavTab.Profile -> ProfileScreen(

                    openAppSettings = openProfileSettings,

                    onAppSettingsConsumed = onProfileSettingsConsumed,

                    modifier = Modifier.fillMaxSize(),

                )

            }



            BrewStatusSheet(

                state = brewState,

                onCancel = { chatViewModel.cancelMachineCommand() },

                onViewChat = {

                    chatViewModel.activeSessionId?.let { onOpenChat(it) }

                },

                modifier = Modifier.align(Alignment.BottomCenter),

            )

        }

        CoffeeBottomNav(selected = selectedTab, onSelect = onTabSelected)

    }



    if (showHelpTopics) {

        ModalBottomSheet(

            onDismissRequest = onHideHelpTopics,

            sheetState = helpSheetState,

        ) {

            HelpTopicsSheet(

                topics = allTopics,

                onToggleSave = onToggleTopicSaved,

                onSelect = { topic ->

                    onHelpTopic(topic.prompt)

                    onHideHelpTopics()

                },

                onDismiss = onHideHelpTopics,

            )

        }

    }

}

