package com.personaledge.ai.sync

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.personaledge.ai.inference.MemoryContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

private val Context.syncDataStore by preferencesDataStore("sync_prefs")

data class BackendConfig(
    val localUrl: String = "http://192.168.1.100:8080",
    val cloudUrl: String = "",
    val apiKey: String = "",
    val useGpu: Boolean = true,
    val autoTts: Boolean = true,
)

class SyncClient(
    private val context: Context,
    private val database: MemoryDatabase,
) {
    private val dao = database.memoryCacheDao()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val sessionId: String = UUID.randomUUID().toString()

    suspend fun getBackendConfig(): BackendConfig {
        val prefs = context.syncDataStore.data.first()
        return BackendConfig(
            localUrl = prefs[localUrlKey] ?: "http://192.168.1.100:8080",
            cloudUrl = prefs[cloudUrlKey] ?: "",
            apiKey = prefs[apiKeyKey] ?: "",
            useGpu = prefs[useGpuKey] ?: true,
            autoTts = prefs[autoTtsKey] ?: true,
        )
    }

    suspend fun saveBackendConfig(config: BackendConfig) {
        context.syncDataStore.edit { prefs ->
            prefs[localUrlKey] = config.localUrl
            prefs[cloudUrlKey] = config.cloudUrl
            prefs[apiKeyKey] = config.apiKey
            prefs[useGpuKey] = config.useGpu
            prefs[autoTtsKey] = config.autoTts
        }
    }

    suspend fun prefetch(query: String): MemoryContext = withContext(Dispatchers.IO) {
        val config = getBackendConfig()
        val body = JSONObject().put("query", query).toString()
        val response = postWithFallback("/v1/memory/prefetch", body, config)
        if (response != null) {
            val json = JSONObject(response)
            val configObj = json.optJSONObject("config")
            val chunks = json.optJSONArray("chunks")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).getString("content") }
            } ?: emptyList()

            configObj?.let {
                dao.saveConfig(
                    CachedConfigEntity(
                        systemPrompt = it.optString("system_prompt", "You are a helpful personal assistant."),
                        personalityRules = it.optString("personality_rules", ""),
                        tone = it.optString("tone", "friendly"),
                        lastSyncedAt = System.currentTimeMillis(),
                    ),
                )
            }
            if (chunks.isNotEmpty()) {
                dao.insertMemories(
                    chunks.mapIndexed { i, content ->
                        MemoryCacheEntity(
                            id = "prefetch_${System.currentTimeMillis()}_$i",
                            content = content,
                            source = "prefetch",
                            timestamp = System.currentTimeMillis(),
                        )
                    },
                )
            }
            MemoryContext(
                systemPrompt = configObj?.optString("system_prompt") ?: getCachedConfig()?.systemPrompt
                    ?: "You are a helpful personal assistant.",
                personalityRules = configObj?.optString("personality_rules")
                    ?: getCachedConfig()?.personalityRules.orEmpty(),
                memoryChunks = chunks.ifEmpty { dao.getMemoryTexts() },
            )
        } else {
            getOfflineContext()
        }
    }

    suspend fun syncTurn(userMessage: String, assistantMessage: String) = withContext(Dispatchers.IO) {
        val config = getBackendConfig()
        val body = JSONObject()
            .put("user", userMessage)
            .put("assistant", assistantMessage)
            .put("session_id", sessionId)
            .toString()
        val response = postWithFallback("/v1/memory/sync", body, config)
        if (response == null) {
            dao.insertPendingSync(
                PendingSyncEntity(
                    sessionId = sessionId,
                    userMessage = userMessage,
                    assistantMessage = assistantMessage,
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun pullFullSync() = withContext(Dispatchers.IO) {
        val config = getBackendConfig()
        val response = postWithFallback("/v1/sync/pull", "{}", config)
            ?: error("Backend unreachable — check URL and API key in Settings")
        val json = JSONObject(response)
        val configObj = json.getJSONObject("config")
        dao.saveConfig(
            CachedConfigEntity(
                systemPrompt = configObj.getString("system_prompt"),
                personalityRules = configObj.optString("personality_rules", ""),
                tone = configObj.optString("tone", "friendly"),
                lastSyncedAt = System.currentTimeMillis(),
            ),
        )
        val memories = json.optJSONArray("memories") ?: JSONArray()
        val entities = (0 until memories.length()).map { i ->
            val mem = memories.getJSONObject(i)
            MemoryCacheEntity(
                id = mem.getString("id"),
                content = mem.getString("content"),
                source = mem.optString("source", "sync"),
                timestamp = parseTimestamp(mem),
            )
        }
        dao.clearMemories()
        if (entities.isNotEmpty()) {
            dao.insertMemories(entities)
        }
    }

    private fun parseTimestamp(mem: JSONObject): Long {
        if (!mem.has("timestamp")) return System.currentTimeMillis()
        val raw = mem.get("timestamp")
        return when (raw) {
            is Long -> raw
            is Int -> raw.toLong()
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: System.currentTimeMillis()
            else -> System.currentTimeMillis()
        }
    }

    suspend fun pushPendingTurns() = withContext(Dispatchers.IO) {
        val pending = dao.getPendingSyncs()
        if (pending.isEmpty()) return@withContext
        val config = getBackendConfig()
        val turns = JSONArray()
        pending.forEach { turn ->
            turns.put(
                JSONObject()
                    .put("user", turn.userMessage)
                    .put("assistant", turn.assistantMessage)
                    .put("session_id", turn.sessionId)
                    .put("timestamp", turn.timestamp),
            )
        }
        val body = JSONObject().put("turns", turns).toString()
        val response = postWithFallback("/v1/sync/push", body, config) ?: return@withContext
        val json = JSONObject(response)
        if (json.optBoolean("success", false)) {
            dao.deletePendingSyncs(pending.map { it.id })
        }
    }

    private suspend fun getCachedConfig(): CachedConfigEntity? = dao.getConfig()

    private suspend fun getOfflineContext(): MemoryContext {
        val cached = getCachedConfig()
        return MemoryContext(
            systemPrompt = cached?.systemPrompt ?: "You are a helpful personal assistant.",
            personalityRules = cached?.personalityRules.orEmpty(),
            memoryChunks = dao.getMemoryTexts(),
        )
    }

    private fun postWithFallback(path: String, body: String, config: BackendConfig): String? {
        val urls = listOfNotNull(
            config.localUrl.takeIf { it.isNotBlank() },
            config.cloudUrl.takeIf { it.isNotBlank() },
        )
        var authFailure = false
        for (baseUrl in urls) {
            try {
                val request = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}$path")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .apply {
                        if (config.apiKey.isNotBlank()) {
                            addHeader("X-API-Key", config.apiKey)
                        }
                    }
                    .build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> return response.body?.string()
                        response.code == 401 -> authFailure = true
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }
        if (authFailure) {
            error("Invalid API key — use the key from backend .env (default: dev-api-key-change-me)")
        }
        return null
    }

    companion object {
        private val localUrlKey = stringPreferencesKey("local_url")
        private val cloudUrlKey = stringPreferencesKey("cloud_url")
        private val apiKeyKey = stringPreferencesKey("api_key")
        private val useGpuKey = booleanPreferencesKey("use_gpu")
        private val autoTtsKey = booleanPreferencesKey("auto_tts")
    }
}
