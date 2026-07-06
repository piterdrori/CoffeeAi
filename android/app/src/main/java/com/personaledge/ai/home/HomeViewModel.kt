package com.personaledge.ai.home

import android.app.Application
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personaledge.ai.EdgeAiApplication
import com.personaledge.ai.chat.ChatSessionEntity
import com.personaledge.ai.homeDataStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EdgeAiApplication
    private val store = app.chatSessionStore
    private val seededKey = booleanPreferencesKey("sample_chats_seeded")

    val sessions: StateFlow<List<ChatSessionEntity>> = store.sessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteSession(id: String) {
        viewModelScope.launch { store.deleteSession(id) }
    }

    suspend fun ensureSampleChats() {
        if (app.homeDataStore.data.first()[seededKey] == true) return
        if (store.sessions.first().isNotEmpty()) {
            app.homeDataStore.edit { it[seededKey] = true }
            return
        }
        val samples = listOf(
            "CoffeeAI Logo Design" to "Discuss brand colors and AI visual direction",
            "Morning Coffee Recipe" to "Find a strong but smooth espresso idea",
            "Brewing Tips" to "Ask about water temperature and grind size",
            "Coffee Shop Recommendations" to "Discover cozy spots nearby",
        )
        samples.forEachIndexed { index, (title, preview) ->
            store.createSession(
                id = UUID.randomUUID().toString(),
                title = title,
                preview = preview,
                accentIndex = index % 4,
            )
        }
        app.homeDataStore.edit { it[seededKey] = true }
    }
}
