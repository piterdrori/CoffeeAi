package com.personaledge.ai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeText
import com.personaledge.ai.ui.theme.TextMuted

@Composable
fun CoffeeAiMark(
    modifier: Modifier = Modifier,
    color: Color = CoffeeBrown,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.045f, cap = StrokeCap.Round)

        val steamX = w * 0.5f
        val steamTop = h * 0.08f
        val steamBottom = h * 0.38f
        listOf(-w * 0.12f, 0f, w * 0.12f).forEach { offset ->
            val path = Path().apply {
                moveTo(steamX + offset, steamBottom)
                cubicTo(
                    steamX + offset - w * 0.06f, steamBottom - h * 0.12f,
                    steamX + offset + w * 0.06f, steamTop + h * 0.08f,
                    steamX + offset, steamTop,
                )
            }
            drawPath(path, color, style = stroke)
        }

        val barWidth = w * 0.07f
        val barGap = w * 0.05f
        val centerY = h * 0.58f
        val heights = listOf(0.22f, 0.38f, 0.28f, 0.42f, 0.24f)
        val totalWidth = heights.size * barWidth + (heights.size - 1) * barGap
        var x = (w - totalWidth) / 2f
        heights.forEach { fraction ->
            val barH = h * fraction
            drawRoundRect(
                color = color,
                topLeft = Offset(x, centerY - barH / 2f),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
            x += barWidth + barGap
        }
    }
}

@Composable
fun CoffeeAiLogoBlock(
    modifier: Modifier = Modifier,
    markSize: Dp = 120.dp,
    titleSize: TextUnit = 42.sp,
    showByline: Boolean = true,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CoffeeAiMark(modifier = Modifier.size(markSize))
        Text(
            text = "CoffeeAI",
            fontSize = titleSize,
            fontWeight = FontWeight.Bold,
            color = CoffeeBrown,
            letterSpacing = (-1).sp,
        )
        if (showByline) {
            Text(
                text = "by AiXia",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = CoffeeBrown.copy(alpha = 0.75f),
            )
        }
        subtitle?.let {
            Text(
                text = it,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = CoffeeText.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
fun CoffeeAiBrandRow(
    modifier: Modifier = Modifier,
    markSize: Dp = 36.dp,
    titleSize: TextUnit = 20.sp,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CoffeeAiMark(modifier = Modifier.size(markSize))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "CoffeeAI",
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                color = CoffeeBrown,
                letterSpacing = (-0.5).sp,
            )
            subtitle?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = TextMuted,
                )
            }
        }
    }
}
