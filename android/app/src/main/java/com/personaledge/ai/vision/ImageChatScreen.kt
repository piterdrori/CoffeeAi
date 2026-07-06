package com.personaledge.ai.vision

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageChatScreen(
    imageUri: Uri?,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    viewModel: ImageChatViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var prompt by remember { mutableStateOf("Describe this image.") }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Image Chat") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.isE2BAvailable) {
                Text("Download Gemma 4 E2B from Models for image chat.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onPickImage) {
                    Icon(Icons.Default.Image, contentDescription = "Pick image")
                }
                IconButton(onClick = onTakePhoto) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "Take photo")
                }
            }
            imageUri?.let { uri ->
                Image(
                    painter = rememberAsyncImagePainter(uri),
                    contentDescription = "Selected image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Prompt") },
            )
            Button(
                onClick = {
                    imageUri?.let { uri ->
                        val path = copyImageToCache(context, uri)
                        viewModel.analyzeImage(path, prompt)
                    }
                },
                enabled = imageUri != null && state.isE2BAvailable && !state.isLoading,
            ) {
                Text("Analyze")
            }
            state.error?.let { Text(it) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.messages) { msg ->
                    Text("${msg.role}: ${msg.content}")
                }
            }
        }
    }
}

private fun copyImageToCache(context: android.content.Context, uri: Uri): String {
    val cacheFile = File(context.cacheDir, "vision_${System.currentTimeMillis()}.jpg")
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(cacheFile).use { output ->
            input.copyTo(output)
        }
    }
    return cacheFile.absolutePath
}
