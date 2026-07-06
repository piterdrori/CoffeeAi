package com.personaledge.ai.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeCream
import com.personaledge.ai.ui.theme.CoffeeText

private data class LegalSection(
    val title: String,
    val paragraphs: List<String>,
)

@Composable
fun PrivacyPolicyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LegalDocumentScreen(
        title = "Privacy Policy",
        lastUpdated = "July 2026",
        sections = privacyPolicySections,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun TermsOfUseScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LegalDocumentScreen(
        title = "Terms of Use",
        lastUpdated = "July 2026",
        sections = termsOfUseSections,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun LegalDocumentScreen(
    title: String,
    lastUpdated: String,
    sections: List<LegalSection>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CoffeeCream)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = CoffeeBrown)
                Text("Back", color = CoffeeBrown, modifier = Modifier.padding(start = 4.dp))
            }
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = CoffeeText,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.padding(end = 72.dp))
        }
        HorizontalDivider(color = Color(0xFFE8DDD0))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = "Last updated: $lastUpdated",
                fontSize = 13.sp,
                color = CoffeeText.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 16.dp),
            )

            sections.forEach { section ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    Text(
                        text = section.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = CoffeeText,
                    )
                    section.paragraphs.forEach { paragraph ->
                        Text(
                            text = paragraph,
                            fontSize = 14.sp,
                            color = CoffeeText.copy(alpha = 0.78f),
                            lineHeight = 22.sp,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

private val privacyPolicySections = listOf(
    LegalSection(
        title = "Overview",
        paragraphs = listOf(
            "CoffeeAI is built to help you with coffee inspiration, recipes, and conversation — mostly on your phone, without needing the internet for everyday chat and voice.",
            "This policy explains what information CoffeeAI uses and how we protect it.",
        ),
    ),
    LegalSection(
        title = "What stays on your device",
        paragraphs = listOf(
            "Your chats, voice conversations, profile details, coffee preferences, and profile photo are stored locally on your phone.",
            "AI replies are generated on your device. You can delete chats or saved memories at any time from App Settings.",
        ),
    ),
    LegalSection(
        title = "What may sync online",
        paragraphs = listOf(
            "When you are online, CoffeeAI may quietly save conversation memories so your assistant can stay consistent across sessions. This happens automatically — you do not need to manage servers or technical settings.",
            "If you message our team through Help & Support, we receive your name, email (if provided), and message so we can respond.",
        ),
    ),
    LegalSection(
        title = "What we do not sell",
        paragraphs = listOf(
            "We do not sell your personal information.",
            "We do not use your chats for advertising profiles.",
            "We only use support messages and synced memories to operate and improve CoffeeAI.",
        ),
    ),
    LegalSection(
        title = "Your choices",
        paragraphs = listOf(
            "Clear all chats — removes every conversation from this device.",
            "Forget saved memories — removes context CoffeeAI learned from past chats.",
            "You may also uninstall the app to remove local data stored on your phone.",
        ),
    ),
    LegalSection(
        title = "Contact",
        paragraphs = listOf(
            "Questions about privacy? Open Help & Support in the app and send us a message.",
        ),
    ),
)

private val termsOfUseSections = listOf(
    LegalSection(
        title = "Welcome to CoffeeAI",
        paragraphs = listOf(
            "CoffeeAI is your personal coffee companion for chat, voice, recipes, and brewing tips.",
            "By using the app, you agree to these terms. If you do not agree, please uninstall the app.",
        ),
    ),
    LegalSection(
        title = "Using the app responsibly",
        paragraphs = listOf(
            "CoffeeAI provides general information and inspiration. Brewing advice, health notes, and caffeine guidance are not medical or professional advice.",
            "Always use your own judgment — especially with equipment, temperatures, and consumption.",
            "Do not use CoffeeAI for harmful, abusive, or illegal activity.",
        ),
    ),
    LegalSection(
        title = "AI-generated content",
        paragraphs = listOf(
            "Replies are generated automatically and may sometimes be inaccurate or incomplete.",
            "Verify important information before acting on it, especially for health, safety, or purchasing decisions.",
        ),
    ),
    LegalSection(
        title = "Your content",
        paragraphs = listOf(
            "You keep ownership of what you write and share in the app.",
            "You give CoffeeAI permission to process your messages on-device, and to sync memories when online, so the service can work as designed.",
        ),
    ),
    LegalSection(
        title = "Availability",
        paragraphs = listOf(
            "CoffeeAI is provided as-is. Features may change, improve, or be unavailable during maintenance or on older devices.",
            "Offline chat and voice depend on your phone's storage and performance.",
        ),
    ),
    LegalSection(
        title = "Changes & contact",
        paragraphs = listOf(
            "We may update these terms from time to time. Continued use of the app means you accept the updated terms.",
            "Need help? Visit Help & Support in the app to talk with CoffeeAI or message our team.",
        ),
    ),
)
