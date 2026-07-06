package com.personaledge.ai.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
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
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeBrownDark
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun LetsTalkPulseOrb(
    active: Boolean,
    modifier: Modifier = Modifier,
    orbSize: Dp = 220.dp,
) {
    val transition = rememberInfiniteTransition(label = "letsTalkPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (active) 900 else 2200,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = if (active) 0.75f else 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringAlpha",
    )

    Canvas(modifier = modifier.size(orbSize)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val maxR = this.size.minDimension / 2f

        listOf(
            Triple(1.0f, Color(0xFFF2C9A0).copy(alpha = ringAlpha * 0.35f), 0.12f),
            Triple(0.82f, Color(0xFFE8A85C).copy(alpha = ringAlpha * 0.5f), 0.18f),
            Triple(0.64f, Color(0xFFD4894A).copy(alpha = ringAlpha * 0.65f), 0.22f),
            Triple(0.46f, Color(0xFFB86A38).copy(alpha = ringAlpha * 0.8f), 0.26f),
        ).forEach { (scale, color, stroke) ->
            val radius = maxR * scale * pulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color, color.copy(alpha = 0.05f)),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
            drawCircle(
                color = color.copy(alpha = 0.9f),
                radius = radius,
                center = center,
                style = Stroke(width = maxR * stroke),
            )
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(CoffeeBrown, CoffeeBrownDark),
                center = center,
                radius = maxR * 0.28f * pulse,
            ),
            radius = maxR * 0.28f * pulse,
            center = center,
        )
    }
}
