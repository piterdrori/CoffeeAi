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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.personaledge.ai.ui.components.CoffeeAiMark
import com.personaledge.ai.ui.components.CoffeeSwirlBackground
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeBrownDark
import com.personaledge.ai.ui.theme.CoffeeCream
import com.personaledge.ai.ui.theme.CoffeeText
import java.util.Calendar

private data class QuickAction(
    val label: String,
    val prompt: String,
    val highlighted: Boolean = false,
)

private val quickActions = listOf(
    QuickAction("Find Recipes", "Can you suggest a strong coffee recipe?", highlighted = true),
    QuickAction("Brewing Tips", "What are your best brewing tips for great coffee?"),
    QuickAction("Coffee Facts", "Tell me an interesting coffee fact."),
    QuickAction("Morning Brew", "Help me plan my morning coffee routine."),
    QuickAction("Espresso Guide", "How do I pull a perfect espresso shot?"),
    QuickAction("Shop Picks", "Recommend cozy coffee shops nearby."),
)

@Composable
fun HomeScreen(
    onLetsChat: () -> Unit,
    onLetsTalk: () -> Unit,
    onQuickAction: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val greeting = rememberGreeting()

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
                text = "What would you like to do today?",
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Quick Actions",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = CoffeeText,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = CoffeeText.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            QuickActionsGrid(
                actions = quickActions,
                onAction = onQuickAction,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
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
    actions: List<QuickAction>,
    onAction: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { action ->
                    QuickActionChip(
                        action = action,
                        onClick = { onAction(action.prompt) },
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
    action: QuickAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (action.highlighted) CoffeeBrown else Color.White.copy(alpha = 0.92f)
    val fg = if (action.highlighted) Color.White else CoffeeBrown
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = action.label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
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
