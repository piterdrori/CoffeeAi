package com.personaledge.ai.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Legacy entry point — delegates to the user-facing settings screen. */
@Composable
fun BackendConfigScreen() {
    SettingsScreen(modifier = Modifier.fillMaxSize())
}
