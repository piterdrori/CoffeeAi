package com.personaledge.ai.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.personaledge.ai.ui.theme.Accent
import com.personaledge.ai.ui.theme.AccentDim
import kotlin.math.cos
import kotlin.math.sin

enum class OrbState {
    Idle,
    Listening,
    Thinking,
    Speaking,
}

@Composable
fun EdgeOrb(
    state: OrbState,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val breathe by transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state) {
                    OrbState.Listening -> 600
                    OrbState.Speaking -> 400
                    OrbState.Thinking -> 900
                    OrbState.Idle -> 2400
                },
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    val morph by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state) {
                    OrbState.Thinking -> 3000
                    else -> 8000
                },
                easing = LinearEasing,
            ),
        ),
        label = "morph",
    )
    val glowAlpha = when (state) {
        OrbState.Listening -> 0.55f
        OrbState.Speaking -> 0.45f
        OrbState.Thinking -> 0.35f
        OrbState.Idle -> 0.2f
    }

    Canvas(modifier = modifier.size(size)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val baseRadius = this.size.minDimension / 2f * breathe

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Accent.copy(alpha = glowAlpha), Color.Transparent),
                center = center,
                radius = baseRadius * 1.4f,
            ),
            radius = baseRadius * 1.4f,
            center = center,
        )

        for (i in 0 until 3) {
            val angle = Math.toRadians((morph + i * 120).toDouble())
            val offsetX = (cos(angle) * baseRadius * 0.18f).toFloat()
            val offsetY = (sin(angle) * baseRadius * 0.18f).toFloat()
            val layerCenter = Offset(center.x + offsetX, center.y + offsetY)
            val layerAlpha = when (state) {
                OrbState.Listening -> 0.85f - i * 0.15f
                OrbState.Thinking -> 0.7f - i * 0.12f
                OrbState.Speaking -> 0.75f - i * 0.14f
                OrbState.Idle -> 0.5f - i * 0.1f
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Accent.copy(alpha = layerAlpha),
                        AccentDim.copy(alpha = layerAlpha * 0.5f),
                        Color.Transparent,
                    ),
                    center = layerCenter,
                    radius = baseRadius * (0.85f - i * 0.08f),
                ),
                radius = baseRadius * (0.85f - i * 0.08f),
                center = layerCenter,
            )
        }
    }
}
