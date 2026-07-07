package com.personaledge.ai.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val CoffeeLightScheme = lightColorScheme(
    primary = CoffeeBrown,
    onPrimary = Color.White,
    primaryContainer = CoffeeCreamDeep,
    onPrimaryContainer = CoffeeText,
    secondary = CoffeeBrownDark,
    onSecondary = Color.White,
    background = CoffeeCream,
    onBackground = CoffeeText,
    surface = Color.White,
    onSurface = CoffeeText,
    onSurfaceVariant = CoffeeText.copy(alpha = 0.72f),
    outline = CoffeeText.copy(alpha = 0.45f),
    surfaceVariant = Color(0xFFF5EDE4),
    error = Error,
    onError = Color.White,
)

@Composable
fun CoffeeAiTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = CoffeeCream.toArgb()
            window.navigationBarColor = Color.White.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = CoffeeLightScheme,
        typography = Typography,
        content = content,
    )
}
