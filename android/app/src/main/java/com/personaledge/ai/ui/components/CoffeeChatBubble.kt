package com.personaledge.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeText

@Composable
fun CoffeeChatBubble(
    role: String,
    content: String,
    isStreaming: Boolean = false,
    imageUri: String? = null,
    modifier: Modifier = Modifier,
    showTtsButton: Boolean = false,
    isTtsPlaying: Boolean = false,
    onTtsClick: (() -> Unit)? = null,
) {
    val isUser = role == "user"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!isUser) {
            AssistantAvatar(modifier = Modifier.padding(end = 10.dp))
        }

        Column(
            modifier = Modifier.fillMaxWidth(0.78f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            imageUri?.let { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .padding(bottom = 6.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.95f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                val text = content.ifBlank { if (isStreaming) "…" else "" }
                Text(
                    text = text,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = CoffeeText,
                    fontWeight = FontWeight.Normal,
                )
            }

            if (showTtsButton && onTtsClick != null) {
                ReplyTtsButton(
                    isPlaying = isTtsPlaying,
                    onClick = onTtsClick,
                    modifier = Modifier.padding(top = 4.dp, start = 2.dp),
                )
            }
        }

        if (isUser) {
            UserAvatar(modifier = Modifier.padding(start = 10.dp))
        }
    }
}

@Composable
private fun ReplyTtsButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (isPlaying) "Stop reading this reply" else "Read this reply aloud"
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(
                if (isPlaying) CoffeeBrown.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.85f),
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            tint = if (isPlaying) CoffeeBrown else CoffeeText.copy(alpha = 0.55f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun AssistantAvatar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        CoffeeAiMark(
            modifier = Modifier.size(26.dp),
            color = CoffeeBrown,
        )
    }
}

@Composable
private fun UserAvatar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(CoffeeBrown.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "☕",
            fontSize = 18.sp,
        )
    }
}
