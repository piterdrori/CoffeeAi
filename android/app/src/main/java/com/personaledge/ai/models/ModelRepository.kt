package com.personaledge.ai.models

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.modelDataStore by preferencesDataStore("model_prefs")

class ModelRepository(private val context: Context) {
    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    private val activeModelKey = stringPreferencesKey("active_model_id")
    private val hfTokenKey = stringPreferencesKey("hf_token")

    val activeModelId: Flow<String?> = context.modelDataStore.data.map { it[activeModelKey] }

    val hfToken: Flow<String?> = context.modelDataStore.data.map { it[hfTokenKey] }

    fun getModelFile(entry: ModelEntry): File = File(modelsDir, entry.fileName)

    /** True if file exists and is at least 90% of expected size (avoids corrupt partial downloads). */
    fun isDownloaded(entry: ModelEntry): Boolean {
        val file = getModelFile(entry)
        if (!file.exists() || file.length() == 0L) return false
        val minBytes = (entry.sizeBytes * 0.90).toLong().coerceAtLeast(1L)
        return file.length() >= minBytes
    }

    fun downloadProgress(entry: ModelEntry): Float {
        val file = getModelFile(entry)
        if (!file.exists() || entry.sizeBytes <= 0) return 0f
        return (file.length().toFloat() / entry.sizeBytes).coerceIn(0f, 1f)
    }

    fun getDownloadedSize(entry: ModelEntry): Long = getModelFile(entry).length()

    suspend fun setActiveModel(id: String) {
        context.modelDataStore.edit { prefs ->
            prefs[activeModelKey] = id
        }
    }

    suspend fun setHfToken(token: String) {
        context.modelDataStore.edit { prefs ->
            prefs[hfTokenKey] = token
        }
    }

    suspend fun getHfToken(): String? = hfToken.first()

    fun importFromUri(entry: ModelEntry, sourceUri: Uri): Boolean {
        return try {
            val dest = getModelFile(entry)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return false
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun deleteModel(entry: ModelEntry) {
        getModelFile(entry).delete()
    }

    /** Detect HTML/JSON error pages saved instead of a .litertlm binary. */
    fun looksLikeErrorPage(file: File): Boolean {
        if (!file.exists() || file.length() < 16) return true
        val head = file.inputStream().use { input ->
            val buf = ByteArray(256)
            val n = input.read(buf)
            if (n <= 0) "" else String(buf, 0, n, Charsets.US_ASCII).trimStart()
        }
        return head.startsWith("<!") ||
            head.startsWith("{") ||
            head.contains("Access Denied", ignoreCase = true) ||
            head.contains("Unauthorized", ignoreCase = true)
    }

    fun modelsDir(): File = modelsDir
}

enum class DownloadStatus {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    COMPLETE,
    FAILED,
}

data class ModelState(
    val entry: ModelEntry,
    val status: DownloadStatus,
    val downloadedBytes: Long = 0L,
    val localPath: String? = null,
    val errorMessage: String? = null,
)
