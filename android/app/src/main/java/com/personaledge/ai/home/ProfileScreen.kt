package com.personaledge.ai.home

import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.personaledge.ai.settings.SettingsScreen
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeBrownDark
import com.personaledge.ai.ui.theme.CoffeeCream
import com.personaledge.ai.ui.theme.CoffeeText
import java.io.File

private val beanTypeChoices = listOf("", "Arabica", "Robusta", "Blend", "Single origin", "Decaf", "Other")
private val roastChoices = listOf("", "Light", "Medium", "Medium-dark", "Dark")
private val originChoices = listOf("", "Colombia", "Ethiopia", "Brazil", "Kenya", "Guatemala", "Italy (blend)", "Other")

@Composable
fun ProfileScreen(
    openAppSettings: Boolean = false,
    onAppSettingsConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showPersonalDialog by remember { mutableStateOf(false) }
    var showCoffeeDialog by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showAppSettings by remember { mutableStateOf(false) }
    var settingsShowsDocument by remember { mutableStateOf(false) }
    var pendingAvatarPath by remember { mutableStateOf(state.profile.avatarPath) }

    LaunchedEffect(openAppSettings) {
        if (openAppSettings) {
            showAppSettings = true
            onAppSettingsConsumed()
        }
    }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            viewModel.persistAvatarUri(context, it)?.let { path ->
                pendingAvatarPath = path
                viewModel.savePersonalDetails(
                    displayName = state.profile.displayName,
                    email = state.profile.email,
                    avatarPath = path,
                )
            }
        }
    }

    when {
        showAppSettings -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .background(CoffeeCream)
                    .statusBarsPadding(),
            ) {
                if (!settingsShowsDocument) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White)
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { showAppSettings = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = CoffeeBrown)
                            Text("Back", color = CoffeeBrown, modifier = Modifier.padding(start = 4.dp))
                        }
                        Text(
                            text = "App Settings",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = CoffeeText,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.size(72.dp))
                    }
                    HorizontalDivider(color = Color(0xFFE8DDD0))
                }
                SettingsScreen(
                    modifier = Modifier.fillMaxSize(),
                    onOpenHelp = {
                        showAppSettings = false
                        showHelp = true
                    },
                    onOverlayChange = { settingsShowsDocument = it },
                )
            }
            return
        }
        showHelp -> {
            HelpSupportScreen(
                onBack = { showHelp = false },
                profileViewModel = viewModel,
                modifier = modifier,
            )
            return
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CoffeeCream)
            .statusBarsPadding(),
    ) {
        ProfileTopBar(onEdit = { showPersonalDialog = true })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ProfileAvatar(
                avatarPath = pendingAvatarPath.ifBlank { state.profile.avatarPath },
                initials = state.profile.initials,
                registered = state.profile.isRegistered,
                onPickImage = { pickAvatar.launch("image/*") },
            )

            Text(
                text = state.profile.displayName.ifBlank { "Coffee Explorer" },
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = CoffeeText,
                modifier = Modifier.padding(top = 16.dp),
            )

            val subtitle = when {
                state.profile.isRegistered && state.profile.memberSinceLabel.isNotBlank() ->
                    "Member since ${state.profile.memberSinceLabel}"
                state.profile.isRegistered -> "CoffeeAI member"
                else -> "Tap Edit to add your details"
            }
            Text(
                text = subtitle,
                fontSize = 14.sp,
                color = CoffeeText.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 4.dp),
            )

            state.profile.coffeePreferences.summary.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = summary,
                    fontSize = 13.sp,
                    color = CoffeeBrown,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            ProfileStatsBar(
                stats = state.stats,
                onSyncClick = { viewModel.syncMemory() },
            )

            Spacer(modifier = Modifier.height(24.dp))

            ProfileMenuRow(
                icon = Icons.Default.Person,
                title = "Personal Details",
                subtitle = if (state.profile.isRegistered) "Name, email & profile photo" else "Register your profile",
                onClick = { showPersonalDialog = true },
            )
            ProfileMenuRow(
                icon = Icons.Default.LocalCafe,
                title = "My Coffee Beans",
                subtitle = state.profile.coffeePreferences.summary.ifBlank {
                    "Add your beans so CoffeeAI can tune recipes"
                },
                onClick = { showCoffeeDialog = true },
            )
            ProfileMenuRow(
                icon = Icons.Default.Settings,
                title = "App Settings",
                subtitle = "Voice, privacy & about CoffeeAI",
                onClick = { showAppSettings = true },
            )
            ProfileMenuRow(
                icon = Icons.Default.HelpOutline,
                title = "Help & Support",
                subtitle = "Ask CoffeeAI or message our team",
                onClick = { showHelp = true },
            )
        }
    }

    if (showPersonalDialog) {
        PersonalDetailsDialog(
            profile = state.profile,
            avatarPath = pendingAvatarPath.ifBlank { state.profile.avatarPath },
            onDismiss = { showPersonalDialog = false },
            onPickImage = { pickAvatar.launch("image/*") },
            onSave = { name, email ->
                viewModel.savePersonalDetails(name, email, pendingAvatarPath.ifBlank { state.profile.avatarPath })
                showPersonalDialog = false
            },
        )
    }

    if (showCoffeeDialog) {
        CoffeePreferencesDialog(
            preferences = state.profile.coffeePreferences,
            onDismiss = { showCoffeeDialog = false },
            onSave = { prefs ->
                viewModel.saveCoffeePreferences(prefs)
                showCoffeeDialog = false
            },
        )
    }
}

@Composable
private fun ProfileTopBar(onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.size(48.dp))
        Text(
            text = "Profile",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = CoffeeText,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = null, tint = CoffeeBrown, modifier = Modifier.size(18.dp))
            Text("Edit", color = CoffeeBrown, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(start = 4.dp))
        }
    }
    HorizontalDivider(color = Color(0xFFE8DDD0), thickness = 1.dp)
}

@Composable
private fun ProfileAvatar(
    avatarPath: String,
    initials: String,
    registered: Boolean,
    onPickImage: () -> Unit,
) {
    Box(contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier = Modifier
                .size(108.dp)
                .clip(CircleShape)
                .background(if (registered) Color(0xFFF5DCC8) else Color(0xFFEDE0D4))
                .clickable(onClick = onPickImage),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarPath.isNotBlank() && File(avatarPath).exists()) {
                AsyncImage(
                    model = File(avatarPath),
                    contentDescription = "Profile photo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = initials,
                    fontSize = if (initials == "☕") 40.sp else 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = CoffeeBrown,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(CoffeeBrown)
                .clickable(onClick = onPickImage),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.AddAPhoto, contentDescription = "Add photo", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ProfileStatsBar(stats: ProfileStats, onSyncClick: () -> Unit) {
    val syncValue = when (stats.memorySyncState) {
        MemorySyncState.Synced -> "Synced"
        MemorySyncState.Syncing -> "…"
        MemorySyncState.Pending -> "Pending"
        MemorySyncState.Disconnected -> "Retry"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(listOf(Color(0xFFB87A4A), CoffeeBrown, CoffeeBrownDark)),
            )
            .padding(vertical = 20.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        StatItem(value = stats.chatCount.toString(), label = "Chats")
        StatItem(value = stats.pinnedCount.toString(), label = "Pinned")
        StatItem(
            value = syncValue,
            label = "Memory",
            subtitle = stats.memorySyncLabel,
            onClick = onSyncClick,
        )
    }
}

@Composable
private fun StatItem(
    value: String,
    label: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onClick != null) {
                Icon(Icons.Default.Sync, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(14.dp))
            }
            Text(text = value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Text(text = label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 4.dp))
        subtitle?.let {
            Text(
                text = it,
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ProfileMenuRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(CoffeeBrown),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = CoffeeText)
            Text(subtitle, fontSize = 13.sp, color = CoffeeText.copy(alpha = 0.55f), modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = CoffeeText.copy(alpha = 0.35f))
    }
}

@Composable
private fun PersonalDetailsDialog(
    profile: UserProfile,
    avatarPath: String,
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onSave: (name: String, email: String) -> Unit,
) {
    var name by remember(profile) { mutableStateOf(profile.displayName) }
    var email by remember(profile) { mutableStateOf(profile.email) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (profile.isRegistered) "Personal Details" else "Register Profile",
                fontWeight = FontWeight.Bold,
                color = CoffeeText,
            )
        },
        text = {
            ProfileFormTheme {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Your details stay on this device only.", fontSize = 13.sp, color = CoffeeText.copy(alpha = 0.65f))
                    TextButton(onClick = onPickImage) {
                        Icon(Icons.Default.AddAPhoto, contentDescription = null, tint = CoffeeBrown)
                        Text(
                            if (avatarPath.isNotBlank()) "Change profile photo" else "Add profile photo",
                            color = CoffeeBrown,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    ProfileLabeledField(label = "Full name") {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = profileFieldColors(),
                        )
                    }
                    ProfileLabeledField(label = "Email (optional)") {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = profileFieldColors(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, email) },
                enabled = name.isNotBlank(),
                colors = profilePrimaryButtonColors(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = CoffeeBrown) }
        },
        containerColor = CoffeeCream,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoffeePreferencesDialog(
    preferences: CoffeePreferences,
    onDismiss: () -> Unit,
    onSave: (CoffeePreferences) -> Unit,
) {
    var beanName by remember(preferences) { mutableStateOf(preferences.beanName) }
    var beanType by remember(preferences) { mutableStateOf(preferences.beanType) }
    var origin by remember(preferences) {
        mutableStateOf(
            when {
                preferences.origin in originChoices.drop(1) -> preferences.origin
                preferences.origin.isNotBlank() -> "Other"
                else -> ""
            },
        )
    }
    var customOrigin by remember(preferences) {
        mutableStateOf(
            if (preferences.origin !in originChoices.drop(1) && preferences.origin.isNotBlank()) {
                preferences.origin
            } else {
                ""
            },
        )
    }
    var roast by remember(preferences) { mutableStateOf(preferences.roastLevel) }
    var notes by remember(preferences) { mutableStateOf(preferences.notes) }

    val resolvedOrigin = if (origin == "Other") customOrigin.trim() else origin
    val canSave = beanName.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My Coffee Beans", fontWeight = FontWeight.Bold, color = CoffeeText) },
        text = {
            ProfileFormTheme {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "CoffeeAI adjusts your brew recipes to match these beans.",
                        fontSize = 13.sp,
                        color = CoffeeText.copy(alpha = 0.65f),
                    )
                    ProfileLabeledField(label = "Bean name or brand") {
                        CoffeeProfileTextField(
                            value = beanName,
                            onValueChange = { beanName = it },
                            placeholder = "e.g. Lavazza Qualità Oro, Ethiopian Yirgacheffe",
                        )
                    }
                    PreferenceDropdown("Bean type", beanType, beanTypeChoices.drop(1)) { beanType = it }
                    PreferenceDropdown("Origin", origin, originChoices.drop(1)) { selected ->
                        origin = selected
                        if (selected != "Other") customOrigin = ""
                    }
                    if (origin == "Other") {
                        ProfileLabeledField(label = "Origin (custom)") {
                            CoffeeProfileTextField(
                                value = customOrigin,
                                onValueChange = { customOrigin = it },
                                placeholder = "e.g. Sumatra, Nicaragua",
                            )
                        }
                    }
                    PreferenceDropdown("Roast level", roast, roastChoices.drop(1)) { roast = it }
                    ProfileLabeledField(label = "Tasting notes (optional)") {
                        CoffeeProfileTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            placeholder = "e.g. nutty, chocolate, medium body",
                            singleLine = false,
                            minLines = 2,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        CoffeePreferences(
                            beanName = beanName.trim(),
                            beanType = beanType,
                            origin = resolvedOrigin,
                            roastLevel = roast,
                            notes = notes.trim(),
                        ),
                    )
                },
                enabled = canSave,
                colors = profilePrimaryButtonColors(),
            ) { Text("Save", color = Color.White) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = CoffeeBrown) }
        },
        containerColor = CoffeeCream,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreferenceDropdown(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ProfileLabeledField(label = label) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = value.ifBlank { "" },
                onValueChange = {},
                readOnly = true,
                placeholder = { Text("Select…", color = CoffeeText.copy(alpha = 0.55f)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                colors = profileDropdownMenuColors(),
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Color.White),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, color = CoffeeText) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
