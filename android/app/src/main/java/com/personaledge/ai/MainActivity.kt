package com.personaledge.ai

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.personaledge.ai.navigation.AppNavigation
import com.personaledge.ai.ui.theme.CoffeeAiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Compose handles keyboard via imePadding(); avoid double-resize gap.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        setContent {
            CoffeeAiTheme {
                AppNavigation()
            }
        }
    }
}
