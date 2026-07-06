package com.personaledge.ai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.personaledge.ai.ui.theme.CoffeeCream
import com.personaledge.ai.ui.theme.CoffeeCreamDeep
import kotlin.random.Random

@Composable
fun CoffeeSwirlBackground(modifier: Modifier = Modifier) {
    val sparkles = rememberSparkleOffsets()
    Canvas(modifier = modifier) {
        drawRect(color = CoffeeCream)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(CoffeeCreamDeep.copy(alpha = 0.85f), Color.Transparent),
                center = Offset(size.width * 0.35f, size.height * 0.22f),
                radius = size.width * 0.75f,
            ),
            radius = size.width * 0.75f,
            center = Offset(size.width * 0.35f, size.height * 0.22f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFD4B896).copy(alpha = 0.6f), Color.Transparent),
                center = Offset(size.width * 0.7f, size.height * 0.35f),
                radius = size.width * 0.55f,
            ),
            radius = size.width * 0.55f,
            center = Offset(size.width * 0.7f, size.height * 0.35f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFC9A87C).copy(alpha = 0.45f), Color.Transparent),
                center = Offset(size.width * 0.5f, size.height * 0.55f),
                radius = size.width * 0.65f,
            ),
            radius = size.width * 0.65f,
            center = Offset(size.width * 0.5f, size.height * 0.55f),
        )

        sparkles.forEach { (x, y, alpha) ->
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = 2.5f,
                center = Offset(x * size.width, y * size.height),
            )
        }
    }
}

@Composable
private fun rememberSparkleOffsets(): List<Triple<Float, Float, Float>> {
    return remember {
        List(18) {
            Triple(
                Random.nextFloat(),
                Random.nextFloat() * 0.7f,
                Random.nextFloat() * 0.35f + 0.15f,
            )
        }
    }
}
