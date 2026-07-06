package com.personaledge.ai

import android.app.Application
import com.personaledge.ai.models.ModelRepository
import com.personaledge.ai.sync.MemoryDatabase
import com.personaledge.ai.sync.SyncClient

class EdgeAiApplication : Application() {
    lateinit var modelRepository: ModelRepository
        private set

    lateinit var syncClient: SyncClient
        private set

    lateinit var memoryDatabase: MemoryDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        modelRepository = ModelRepository(this)
        memoryDatabase = MemoryDatabase.getInstance(this)
        syncClient = SyncClient(this, memoryDatabase)
    }
}