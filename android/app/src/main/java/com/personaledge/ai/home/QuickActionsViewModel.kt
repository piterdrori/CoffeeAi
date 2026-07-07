package com.personaledge.ai.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personaledge.ai.EdgeAiApplication
import com.personaledge.ai.coffee.CoffeeActionStore
import com.personaledge.ai.coffee.CoffeeRecipeEntity
import com.personaledge.ai.coffee.QuickActionEntity
import com.personaledge.ai.coffee.QuickActionKind
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class QuickActionsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = (application as EdgeAiApplication).coffeeActionStore

    val enabledActions: StateFlow<List<QuickActionEntity>> = store.enabledQuickActions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allActions: StateFlow<List<QuickActionEntity>> = store.allQuickActions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { store.seedDefaultsIfNeeded() }
    }

    fun toggleAction(id: String, enabled: Boolean) {
        viewModelScope.launch {
            val action = allActions.value.find { it.id == id } ?: return@launch
            store.upsertQuickAction(action.copy(enabled = enabled))
        }
    }

    fun deleteAction(id: String) {
        viewModelScope.launch { store.deleteCustomQuickAction(id) }
    }

    fun addCustomDrink(title: String, grams: Int, water: Int, milk: Int, foam: Int) {
        viewModelScope.launch {
            val action = CoffeeActionStore.newCustomDrinkAction(title, grams, water, milk, foam)
            store.upsertQuickAction(action)
        }
    }

    fun addMachineControl(title: String, commandKey: String) {
        viewModelScope.launch {
            val action = QuickActionEntity(
                id = java.util.UUID.randomUUID().toString(),
                title = title,
                kind = QuickActionKind.MACHINE_CONTROL.name,
                actionKey = commandKey,
                enabled = true,
                sortOrder = 200,
                isBuiltIn = false,
            )
            store.upsertQuickAction(action)
        }
    }
}

class RecipesViewModel(application: Application) : AndroidViewModel(application) {
    private val store = (application as EdgeAiApplication).coffeeActionStore

    val recipes: StateFlow<List<CoffeeRecipeEntity>> = store.recipes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveRecipe(recipe: CoffeeRecipeEntity) {
        viewModelScope.launch { store.saveRecipe(recipe) }
    }

    fun deleteRecipe(id: String) {
        viewModelScope.launch { store.deleteRecipe(id) }
    }
}
