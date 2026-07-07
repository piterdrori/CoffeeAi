package com.personaledge.ai.home

import android.app.Activity
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personaledge.ai.coffee.CoffeeRecipeEntity
import com.personaledge.ai.coffee.QuickActionEntity
import com.personaledge.ai.coffee.QuickActionKind
import com.personaledge.ai.ui.components.CoffeeAiMark
import com.personaledge.ai.ui.components.CoffeeSwirlBackground
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeBrownDark
import com.personaledge.ai.ui.theme.CoffeeCream
import com.personaledge.ai.ui.theme.CoffeeText
import java.util.Calendar

@Composable
fun HomeScreen(
    onLetsChat: () -> Unit,
    onLetsTalk: () -> Unit,
    onBrewRecipe: (CoffeeRecipeEntity) -> Unit,
    onMachineAction: (QuickActionEntity) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFavoriteBeverages: () -> Unit,
    onOpenManageMachine: () -> Unit,
    modifier: Modifier = Modifier,
    quickActionsViewModel: QuickActionsViewModel = viewModel(),
    recipesViewModel: RecipesViewModel = viewModel(),
) {
    val view = LocalView.current
    val greeting = rememberGreeting()
    val actions by quickActionsViewModel.enabledActions.collectAsState()
    val recipes by recipesViewModel.recipes.collectAsState()
    val controls = actions.filter { it.kind == QuickActionKind.MACHINE_CONTROL.name }

    SideEffect {
        val window = (view.context as Activity).window
        window.statusBarColor = CoffeeCream.toArgb()
        window.navigationBarColor = Color.White.toArgb()
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
    }

    Box(modifier = modifier.fillMaxSize()) {
        CoffeeSwirlBackground(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            ChatHubTopBar(onOpenSettings = onOpenSettings)

            Text(
                text = greeting,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = CoffeeText,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                text = "Tap a favorite beverage to brew, or control your machine.",
                fontSize = 15.sp,
                color = CoffeeText.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                HubActionCard(
                    title = "Let's Talk",
                    icon = Icons.Default.Mic,
                    onClick = onLetsTalk,
                    modifier = Modifier.weight(1f),
                )
                HubActionCard(
                    title = "Let's Chat",
                    icon = Icons.Default.Chat,
                    onClick = onLetsChat,
                    modifier = Modifier.weight(1f),
                )
            }

            FavoriteBeveragesHeader(onOpenFavoriteBeverages = onOpenFavoriteBeverages)

            if (recipes.isEmpty()) {
                EmptyFavoriteBeveragesHint(onAdd = onOpenFavoriteBeverages)
            } else {
                FavoriteBeveragesGrid(
                    recipes = recipes,
                    onBrew = onBrewRecipe,
                )
            }

            if (controls.isNotEmpty()) {
                MachineSectionHeader(onManage = onOpenManageMachine)
                QuickActionsGrid(
                    actions = controls,
                    onAction = onMachineAction,
                    highlightedFirst = false,
                    isControl = true,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FavoriteBeveragesHeader(onOpenFavoriteBeverages: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 14.dp)
            .clickable(onClick = onOpenFavoriteBeverages),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Favorite Beverages",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = CoffeeText,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Open favorite beverages",
            tint = CoffeeText.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun MachineSectionHeader(onManage: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp)
            .clickable(onClick = onManage),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Machine",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = CoffeeText.copy(alpha = 0.5f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Manage machine controls",
            tint = CoffeeText.copy(alpha = 0.35f),
            modifier = Modifier
                .padding(start = 2.dp)
                .size(16.dp),
        )
    }
}

@Composable
private fun EmptyFavoriteBeveragesHint(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.85f))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No favorite beverages yet",
            fontWeight = FontWeight.SemiBold,
            color = CoffeeText.copy(alpha = 0.7f),
        )
        Text(
            text = "Create one in Favorite Beverages and it will appear here.",
            fontSize = 13.sp,
            color = CoffeeText.copy(alpha = 0.45f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        TextButton(onClick = onAdd) {
            Text("Add favorite beverage", color = CoffeeBrown, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FavoriteBeveragesGrid(
    recipes: List<CoffeeRecipeEntity>,
    onBrew: (CoffeeRecipeEntity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        recipes.chunked(2).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEachIndexed { colIndex, recipe ->
                    val highlighted = rowIndex == 0 && colIndex == 0
                    BeverageChip(
                        label = recipe.name,
                        highlighted = highlighted,
                        onClick = { onBrew(recipe) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BeverageChip(
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (highlighted) CoffeeBrown else Color.White.copy(alpha = 0.92f)
    val fg = if (highlighted) Color.White else CoffeeBrown
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun ChatHubTopBar(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(CoffeeBrown),
            contentAlignment = Alignment.Center,
        ) {
            CoffeeAiMark(modifier = Modifier.size(24.dp), color = Color.White)
        }
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(
                text = "CoffeeAI",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = CoffeeText,
            )
            Text(
                text = "by AiXia",
                fontSize = 12.sp,
                color = CoffeeText.copy(alpha = 0.55f),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = CoffeeBrown,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun HubActionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(200.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFB87A4A),
                        CoffeeBrown,
                        CoffeeBrownDark,
                    ),
                ),
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier
                .size(18.dp)
                .align(Alignment.TopEnd),
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(52.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun QuickActionsGrid(
    actions: List<QuickActionEntity>,
    onAction: (QuickActionEntity) -> Unit,
    highlightedFirst: Boolean,
    isControl: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.chunked(2).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEachIndexed { colIndex, action ->
                    val highlighted = highlightedFirst && rowIndex == 0 && colIndex == 0
                    QuickActionChip(
                        label = action.title,
                        highlighted = highlighted,
                        isControl = isControl,
                        onClick = { onAction(action) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun QuickActionChip(
    label: String,
    highlighted: Boolean,
    isControl: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (highlighted) CoffeeBrown else Color.White.copy(alpha = 0.92f)
    val fg = if (highlighted) Color.White else CoffeeBrown
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isControl) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    tint = fg.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = label,
                color = fg,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun rememberGreeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val timeGreeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Good night"
    }
    return "$timeGreeting, coffee lover"
}
