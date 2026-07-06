package com.personaledge.ai.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personaledge.ai.EdgeAiApplication
import com.personaledge.ai.chat.ChatSessionEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FavoritesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EdgeAiApplication
    private val store = app.chatSessionStore

    val favorites: StateFlow<List<ChatSessionEntity>> = store.favorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFavorite(sessionId: String, favorite: Boolean) {
        viewModelScope.launch { store.setFavorite(sessionId, favorite) }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch { store.deleteSession(sessionId) }
    }

    suspend fun ensureSampleFavorites() {
        var sessions = store.sessions.first()
        if (sessions.isEmpty()) {
            val samples = listOf(
                "CoffeeAI Logo Design" to "Discuss brand colors and AI visual direction",
                "Morning Coffee Recipe" to "Find a strong but smooth espresso idea",
                "Brewing Tips" to "Ask about water temperature and grind size",
                "Coffee Shop Recommendations" to "Discover cozy spots nearby",
            )
            samples.forEachIndexed { index, (title, preview) ->
                store.createSession(
                    id = java.util.UUID.randomUUID().toString(),
                    title = title,
                    preview = preview,
                    accentIndex = index % 4,
                )
            }
            sessions = store.sessions.first()
        }
        val pinTitles = setOf("Morning Coffee Recipe", "Brewing Tips")
        sessions
            .filter { it.title in pinTitles && !it.isFavorite }
            .forEach { store.setFavorite(it.id, true) }
    }
}
