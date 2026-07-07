package com.personaledge.ai.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personaledge.ai.coffee.BrewStatus
import com.personaledge.ai.coffee.BrewState
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeCream
import com.personaledge.ai.ui.theme.CoffeeText

@Composable
fun BrewStatusSheet(
    state: BrewState,
    onCancel: () -> Unit,
    onViewChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.status == BrewStatus.Idle) return

    val statusText = when (state.status) {
        BrewStatus.Preparing -> "Preparing your drink…"
        BrewStatus.Brewing -> "Brewing ${state.displayName}…"
        BrewStatus.Ready -> "${state.displayName} is ready!"
        BrewStatus.Error -> state.error ?: "Something went wrong"
        BrewStatus.Idle -> ""
    }

    val progress = when (state.status) {
        BrewStatus.Preparing -> 0.3f
        BrewStatus.Brewing -> 0.7f
        BrewStatus.Ready -> 1f
        BrewStatus.Error -> 0f
        BrewStatus.Idle -> 0f
    }

    val infiniteTransition = rememberInfiniteTransition(label = "brew_pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size((56 * if (state.status == BrewStatus.Brewing) pulse else 1f).dp)
                    .clip(CircleShape)
                    .background(CoffeeBrown),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.LocalCafe,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }

            Text(
                text = statusText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = CoffeeText,
            )

            if (state.status != BrewStatus.Error) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = CoffeeBrown,
                    trackColor = CoffeeCream,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.status == BrewStatus.Ready) {
                    Button(
                        onClick = onViewChat,
                        colors = ButtonDefaults.buttonColors(containerColor = CoffeeBrown),
                    ) {
                        Text("View chat")
                    }
                } else if (state.status != BrewStatus.Ready) {
                    TextButton(onClick = onCancel) {
                        Text("Cancel", color = CoffeeBrown)
                    }
                }
            }
        }
    }
}
