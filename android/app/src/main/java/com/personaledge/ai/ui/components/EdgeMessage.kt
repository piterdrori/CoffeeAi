package com.personaledge.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.personaledge.ai.ui.theme.Accent
import com.personaledge.ai.ui.theme.Background
import com.personaledge.ai.ui.theme.SurfaceRaised
import com.personaledge.ai.ui.theme.TextMuted

@Composable
fun EdgeMessage(
    role: String,
    content: String,
    isStreaming: Boolean,
    imageUri: String? = null,
    onSpeak: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isUser = role == "user"
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.88f),
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
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isUser) SurfaceRaised else Background)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                val text = content.ifBlank { if (isStreaming) "…" else "" }
                Text(
                    text = text,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                )
            }
            if (!isUser && !isStreaming && content.isNotBlank() && onSpeak != null) {
                IconButton(
                    onClick = onSpeak,
                    modifier = Modifier
                        .padding(top = 4.dp, start = 4.dp)
                        .size(32.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Read aloud",
                        tint = Accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else if (isStreaming && !isUser) {
                Text(
                    text = "streaming",
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
        }
    }
}
