package com.personaledge.ai.sync

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.personaledge.ai.BuildConfig
import com.personaledge.ai.home.ProfileStore
import com.personaledge.ai.inference.MemoryContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

private val Context.syncDataStore by preferencesDataStore("sync_prefs")

private const val DEFAULT_CLOUD_URL = BuildConfig.CLOUD_URL

data class BackendConfig(
    val localUrl: String = "",
    val cloudUrl: String = DEFAULT_CLOUD_URL,
    val apiKey: String = BuildConfig.CLOUD_API_KEY,
    val useGpu: Boolean = false,
    val autoTts: Boolean = true,
)

class SyncClient(
    private val context: Context,
    private val database: MemoryDatabase,
    private val profileStore: ProfileStore,
) {
    private val dao = database.memoryCacheDao()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val quickClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    val sessionId: String = UUID.randomUUID().toString()

    suspend fun getBackendConfig(): BackendConfig {
        val prefs = context.syncDataStore.data.first()
        return BackendConfig(
            localUrl = prefs[localUrlKey].orEmpty(),
            cloudUrl = prefs[cloudUrlKey]?.takeIf { it.isNotBlank() } ?: DEFAULT_CLOUD_URL,
            apiKey = prefs[apiKeyKey]?.takeIf { it.isNotBlank() } ?: BuildConfig.CLOUD_API_KEY,
            useGpu = prefs[useGpuKey] ?: false,
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
        prefetchInternal(query, client)
    }

    /** Best-effort prefetch with a short deadline so chat is not blocked on slow networks. */
    suspend fun prefetchQuick(query: String, timeoutMs: Long = 2_500L): MemoryContext? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                try {
                    prefetchInternal(query, quickClient)
                } catch (_: Exception) {
                    null
                }
            }
        }

    suspend fun offlineContext(): MemoryContext = getOfflineContext()

    private suspend fun prefetchInternal(query: String, httpClient: OkHttpClient): MemoryContext {
        val config = getBackendConfig()
        val body = JSONObject().put("query", query).toString()
        val response = postWithFallback("/v1/memory/prefetch", body, config, httpClient)
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
            return MemoryContext(
                systemPrompt = configObj?.optString("system_prompt") ?: getCachedConfig()?.systemPrompt
                    ?: "You are a helpful personal assistant.",
                personalityRules = configObj?.optString("personality_rules")
                    ?: getCachedConfig()?.personalityRules.orEmpty(),
                memoryChunks = chunks.ifEmpty { dao.getMemoryTexts() },
            )
        } else {
            return getOfflineContext()
        }
    }

    suspend fun disableGpu() {
        val config = getBackendConfig()
        if (config.useGpu) {
            saveBackendConfig(config.copy(useGpu = false))
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
            ?: error("Could not reach CoffeeAI cloud — memories will sync when you're back online")
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

    suspend fun sendSupportMessage(name: String, email: String, message: String): Boolean =
        withContext(Dispatchers.IO) {
            val config = getBackendConfig()
            val body = JSONObject()
                .put("name", name)
                .put("email", email)
                .put("message", message)
                .put("session_id", sessionId)
                .toString()
            postWithFallback("/v1/support/message", body, config) != null
        }

    private suspend fun getCachedConfig(): CachedConfigEntity? = dao.getConfig()

    private suspend fun getOfflineContext(): MemoryContext {
        val cached = getCachedConfig()
        val beanChunk = profileStore.currentCoffeePreferences().memoryChunk()
        val memoryChunks = buildList {
            if (beanChunk != null) add(beanChunk)
            addAll(dao.getMemoryTexts())
        }
        return MemoryContext(
            systemPrompt = cached?.systemPrompt ?: "You are a helpful personal assistant.",
            personalityRules = cached?.personalityRules.orEmpty(),
            memoryChunks = memoryChunks,
        )
    }

    private fun postWithFallback(
        path: String,
        body: String,
        config: BackendConfig,
        httpClient: OkHttpClient = client,
    ): String? {
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
                httpClient.newCall(request).execute().use { response ->
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
            error("Could not authenticate with CoffeeAI cloud")
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
