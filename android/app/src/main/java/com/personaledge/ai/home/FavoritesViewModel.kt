package com.personaledge.ai.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personaledge.ai.EdgeAiApplication
import com.personaledge.ai.chat.ChatSessionEntity
import com.personaledge.ai.coffee.SavedTopicEntity
import com.personaledge.ai.coffee.CoffeeRecipeEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FavoritesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EdgeAiApplication
    private val chatStore = app.chatSessionStore
    private val actionStore = app.coffeeActionStore

    val favorites: StateFlow<List<ChatSessionEntity>> = chatStore.favorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recipes: StateFlow<List<CoffeeRecipeEntity>> = actionStore.recipes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val savedTopics: StateFlow<List<SavedTopicEntity>> = actionStore.savedTopics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allTopics: StateFlow<List<SavedTopicEntity>> = actionStore.allTopics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { actionStore.seedDefaultsIfNeeded() }
    }

    fun setFavorite(sessionId: String, favorite: Boolean) {
        viewModelScope.launch { chatStore.setFavorite(sessionId, favorite) }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch { chatStore.deleteSession(sessionId) }
    }

    fun deleteRecipe(id: String) {
        viewModelScope.launch { actionStore.deleteRecipe(id) }
    }

    fun saveRecipe(recipe: CoffeeRecipeEntity) {
        viewModelScope.launch { actionStore.saveRecipe(recipe) }
    }

    fun toggleTopicSaved(topic: SavedTopicEntity, saved: Boolean) {
        viewModelScope.launch {
            actionStore.upsertTopic(topic.copy(isSaved = saved))
        }
    }
}
