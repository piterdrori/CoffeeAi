package com.personaledge.ai.models

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private val Context.modelDataStore by preferencesDataStore("model_prefs")

class ModelRepository(private val context: Context) {
    private val installMutex = Mutex()

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    private val activeModelKey = stringPreferencesKey("active_model_id")
    private val hfTokenKey = stringPreferencesKey("hf_token")

    val activeModelId: Flow<String?> = context.modelDataStore.data.map { it[activeModelKey] }

    val hfToken: Flow<String?> = context.modelDataStore.data.map { it[hfTokenKey] }

    fun bundledAssetPath(entry: ModelEntry): String = "models/${entry.fileName}"

    fun hasBundledAsset(entry: ModelEntry): Boolean {
        if (!entry.isBundled) return false
        return try {
            context.assets.open(bundledAssetPath(entry)).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getModelFile(entry: ModelEntry): File = File(modelsDir, entry.fileName)

    /** True if file exists and is at least 90% of expected size (avoids corrupt partial downloads). */
    fun isDownloaded(entry: ModelEntry): Boolean {
        val file = getModelFile(entry)
        if (!file.exists() || file.length() == 0L) {
            return entry.isBundled && hasBundledAsset(entry)
        }
        val minBytes = (entry.sizeBytes * 0.90).toLong().coerceAtLeast(1L)
        return file.length() >= minBytes
    }

    fun isInstalledOnDisk(entry: ModelEntry): Boolean {
        val file = getModelFile(entry)
        if (!file.exists() || file.length() == 0L) return false
        val minBytes = (entry.sizeBytes * 0.90).toLong().coerceAtLeast(1L)
        return file.length() >= minBytes
    }

    suspend fun ensureOnDisk(entry: ModelEntry): Boolean = installMutex.withLock {
        withContext(Dispatchers.IO) {
            if (isInstalledOnDisk(entry)) return@withContext true
            if (!entry.isBundled || !hasBundledAsset(entry)) return@withContext false

            val dest = getModelFile(entry)
            if (dest.exists()) dest.delete()

            context.assets.open(bundledAssetPath(entry)).use { input ->
                dest.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
            isInstalledOnDisk(entry)
        }
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
        if (entry.isBundled) {
            // Keep bundled model restorable from APK assets.
            getModelFile(entry).delete()
            return
        }
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
