package com.personaledge.ai.navigation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personaledge.ai.chat.ChatScreen
import com.personaledge.ai.chat.ChatViewModel
import com.personaledge.ai.settings.SettingsScreen
import com.personaledge.ai.ui.components.EdgeScaffold
import com.personaledge.ai.voice.VoiceModeScreen

@Composable
fun AppNavigation() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showVoice by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val chatViewModel: ChatViewModel = viewModel()

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let { chatViewModel.attachImage(it, context) }
    }

    if (showVoice) {
        VoiceModeScreen(
            onBack = { showVoice = false },
            chatViewModel = chatViewModel,
        )
        return
    }

    EdgeScaffold(
        selectedTab = selectedTab,
        onTabSelected = { selectedTab = it },
    ) { modifier ->
        when (selectedTab) {
            0 -> ChatScreen(
                modifier = modifier,
                onVoiceMode = { showVoice = true },
                onAttachImage = { pickImage.launch("image/*") },
                viewModel = chatViewModel,
            )
            1 -> SettingsScreen(modifier = modifier)
        }
    }
}
