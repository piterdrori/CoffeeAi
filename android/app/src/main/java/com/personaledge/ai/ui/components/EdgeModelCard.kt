package com.personaledge.ai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personaledge.ai.models.DownloadStatus
import com.personaledge.ai.ui.theme.Accent
import com.personaledge.ai.ui.theme.Background
import com.personaledge.ai.ui.theme.Error
import com.personaledge.ai.ui.theme.SurfaceRaised
import com.personaledge.ai.ui.theme.TextMuted
import com.personaledge.ai.ui.theme.TextPrimary

@Composable
fun EdgeModelCard(
    name: String,
    description: String,
    sizeLabel: String,
    status: DownloadStatus,
    progress: Float,
    isActive: Boolean,
    error: String?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onActivate: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = SurfaceRaised,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    if (isActive) {
                        Text(
                            "Active",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = Accent,
                        )
                    }
                }
                Text(description, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                Text(sizeLabel, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                if (status == DownloadStatus.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        color = Accent,
                        trackColor = Background,
                    )
                }
                error?.let {
                    Text(it, color = Error, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                }
            }
            when (status) {
                DownloadStatus.COMPLETE -> {
                    Button(
                        onClick = onActivate,
                        enabled = !isActive,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Background,
                        ),
                    ) {
                        Text(if (isActive) "In use" else "Use")
                    }
                    Button(onClick = onDelete) { Text("Delete") }
                }
                DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                    Button(onClick = onCancel) { Text("Cancel") }
                }
                DownloadStatus.FAILED -> {
                    Column(horizontalAlignment = Alignment.End) {
                        Button(onClick = onDownload) { Text("Retry") }
                        Button(onClick = onDelete) { Text("Delete") }
                    }
                }
                else -> {
                    Column(horizontalAlignment = Alignment.End) {
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent,
                                contentColor = Background,
                            ),
                        ) {
                            Text("Download")
                        }
                        Button(onClick = onImport) { Text("Import") }
                        if (error != null) {
                            Button(onClick = onDelete) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
