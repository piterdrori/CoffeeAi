package com.personaledge.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.personaledge.ai.navigation.AppNavigation
import com.personaledge.ai.ui.theme.PersonalEdgeAITheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PersonalEdgeAITheme {
                AppNavigation()
            }
        }
    }
}
