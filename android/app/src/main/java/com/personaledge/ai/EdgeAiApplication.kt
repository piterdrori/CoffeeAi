package com.personaledge.ai

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.personaledge.ai.chat.ChatSessionStore
import com.personaledge.ai.models.ModelCatalog
import com.personaledge.ai.models.ModelRepository
import com.personaledge.ai.sync.MemoryDatabase
import com.personaledge.ai.sync.SyncClient
import com.personaledge.ai.voice.SttManager
import com.personaledge.ai.voice.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

val Context.homeDataStore by preferencesDataStore("home_prefs")

class EdgeAiApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var modelRepository: ModelRepository
        private set

    lateinit var syncClient: SyncClient
        private set

    lateinit var memoryDatabase: MemoryDatabase
        private set

    lateinit var chatSessionStore: ChatSessionStore
        private set

    val ttsManager: TtsManager by lazy { TtsManager(this) }

    val sttManager: SttManager by lazy { SttManager(this) }

    override fun onCreate() {
        super.onCreate()
        modelRepository = ModelRepository(this)
        memoryDatabase = MemoryDatabase.getInstance(this)
        syncClient = SyncClient(this, memoryDatabase)
        chatSessionStore = ChatSessionStore(this)

        appScope.launch {
            val default = ModelCatalog.defaultModel()
            if (default.isBundled) {
                modelRepository.ensureOnDisk(default)
            }
            if (modelRepository.activeModelId.first().isNullOrBlank()) {
                modelRepository.setActiveModel(default.id)
            }
        }
    }
}
