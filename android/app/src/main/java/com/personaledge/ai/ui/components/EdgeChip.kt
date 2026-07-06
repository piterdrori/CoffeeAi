package com.personaledge.ai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personaledge.ai.ui.theme.Accent
import com.personaledge.ai.ui.theme.SurfaceRaised
import com.personaledge.ai.ui.theme.TextPrimary

@Composable
fun EdgeChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = SurfaceRaised,
        border = BorderStroke(1.dp, Accent.copy(alpha = 0.35f)),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = TextPrimary,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
    }
}
