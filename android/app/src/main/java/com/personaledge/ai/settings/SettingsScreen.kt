package com.personaledge.ai.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeCream
import com.personaledge.ai.ui.theme.CoffeeText

private enum class SettingsDocument {
    None,
    Privacy,
    Terms,
}

@Composable
fun SettingsScreen(
    onOpenHelp: () -> Unit = {},
    onOverlayChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var document by remember { mutableStateOf(SettingsDocument.None) }
    var confirmClearChats by remember { mutableStateOf(false) }
    var confirmForgetMemories by remember { mutableStateOf(false) }

    LaunchedEffect(document) {
        onOverlayChange(document != SettingsDocument.None)
    }

    when (document) {
        SettingsDocument.Privacy -> {
            PrivacyPolicyScreen(
                onBack = { document = SettingsDocument.None },
                modifier = modifier,
            )
            return
        }
        SettingsDocument.Terms -> {
            TermsOfUseScreen(
                onBack = { document = SettingsDocument.None },
                modifier = modifier,
            )
            return
        }
        SettingsDocument.None -> Unit
    }

    LaunchedEffect(state.feedbackMessage) {
        if (state.feedbackMessage != null) {
            kotlinx.coroutines.delay(3500)
            viewModel.clearFeedback()
        }
    }

    if (confirmClearChats) {
        ConfirmDialog(
            title = "Clear all chats?",
            message = "Every conversation will be removed from this device. This cannot be undone.",
            confirmLabel = "Clear chats",
            onConfirm = {
                confirmClearChats = false
                viewModel.clearAllChats()
            },
            onDismiss = { confirmClearChats = false },
        )
    }

    if (confirmForgetMemories) {
        ConfirmDialog(
            title = "Forget saved memories?",
            message = "CoffeeAI will forget context it learned from past chats. Your conversations stay on this device.",
            confirmLabel = "Forget memories",
            onConfirm = {
                confirmForgetMemories = false
                viewModel.forgetSavedMemories()
            },
            onDismiss = { confirmForgetMemories = false },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CoffeeCream)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsSectionLabel("Conversation")

        SettingsToggleRow(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            title = "Read replies aloud",
            subtitle = "CoffeeAI speaks assistant messages in chat and voice",
            checked = state.readRepliesAloud,
            enabled = !state.isBusy,
            onCheckedChange = viewModel::setReadRepliesAloud,
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingsSectionLabel("Privacy & data")

        Text(
            text = "Chats and voice run on your phone. Memories save quietly when you're online.",
            fontSize = 13.sp,
            color = CoffeeText.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )

        SettingsActionRow(
            icon = Icons.Default.DeleteOutline,
            title = "Clear all chats",
            subtitle = "Remove every conversation",
            enabled = !state.isBusy,
            onClick = { confirmClearChats = true },
        )
        SettingsActionRow(
            icon = Icons.Default.DeleteOutline,
            title = "Forget saved memories",
            subtitle = "Let CoffeeAI forget past context",
            enabled = !state.isBusy,
            onClick = { confirmForgetMemories = true },
        )

        Spacer(modifier = Modifier.height(8.dp))
        SettingsSectionLabel("About")

        SettingsInfoRow(
            title = "Version",
            value = state.appVersion,
        )
        SettingsActionRow(
            icon = Icons.Default.PrivacyTip,
            title = "Privacy Policy",
            subtitle = "How we handle your data",
            onClick = { document = SettingsDocument.Privacy },
        )
        SettingsActionRow(
            icon = Icons.Default.PrivacyTip,
            title = "Terms of Use",
            subtitle = "Using CoffeeAI",
            onClick = { document = SettingsDocument.Terms },
        )
        SettingsActionRow(
            icon = Icons.AutoMirrored.Filled.HelpOutline,
            title = "Help & Support",
            subtitle = "Ask CoffeeAI or message our team",
            onClick = onOpenHelp,
        )

        state.feedbackMessage?.let { message ->
            Text(
                text = message,
                fontSize = 13.sp,
                color = CoffeeBrown,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
        }
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = CoffeeText.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp, top = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        content()
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsIconBadge(icon)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = CoffeeText)
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    color = CoffeeText.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = CoffeeBrown,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = CoffeeText.copy(alpha = 0.2f),
                ),
            )
        }
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsIconBadge(icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = CoffeeText)
            Text(
                subtitle,
                fontSize = 13.sp,
                color = CoffeeText.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = CoffeeText.copy(alpha = 0.35f),
        )
    }
}

@Composable
private fun SettingsInfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = CoffeeText)
        Text(value, fontSize = 15.sp, color = CoffeeText.copy(alpha = 0.55f))
    }
}

@Composable
private fun SettingsIconBadge(icon: ImageVector) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .padding(0.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CoffeeBrown.copy(alpha = 0.12f))
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = CoffeeBrown, modifier = Modifier.padding(0.dp))
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold, color = CoffeeText) },
        text = { Text(message, color = CoffeeText.copy(alpha = 0.75f)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = CoffeeBrown, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = CoffeeText.copy(alpha = 0.6f))
            }
        },
        containerColor = CoffeeCream,
    )
}
