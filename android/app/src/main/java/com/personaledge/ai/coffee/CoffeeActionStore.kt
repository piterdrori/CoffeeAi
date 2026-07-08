package com.personaledge.ai.coffee

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class CoffeeActionStore(context: Context) {
    private val dao = CoffeeActionDatabase.getInstance(context).coffeeActionDao()

    val recipes: Flow<List<CoffeeRecipeEntity>> = dao.observeRecipes()
    val enabledQuickActions: Flow<List<QuickActionEntity>> = dao.observeEnabledQuickActions()
    val allQuickActions: Flow<List<QuickActionEntity>> = dao.observeAllQuickActions()
    val savedTopics: Flow<List<SavedTopicEntity>> = dao.observeSavedTopics()
    val allTopics: Flow<List<SavedTopicEntity>> = dao.observeAllTopics()

    suspend fun getRecipe(id: String): CoffeeRecipeEntity? = dao.getRecipe(id)

    suspend fun saveRecipe(recipe: CoffeeRecipeEntity) = dao.upsertRecipe(recipe)

    suspend fun deleteRecipe(id: String) = dao.deleteRecipe(id)

    suspend fun upsertQuickAction(action: QuickActionEntity) = dao.upsertQuickAction(action)

    suspend fun deleteCustomQuickAction(id: String) = dao.deleteCustomQuickAction(id)

    suspend fun upsertTopic(topic: SavedTopicEntity) = dao.upsertTopic(topic)

    suspend fun seedDefaultsIfNeeded() {
        if (dao.quickActionCount() == 0) {
            defaultQuickActions().forEach { dao.upsertQuickAction(it) }
        }
        if (dao.topicCount() == 0) {
            defaultHelpTopics().forEach { dao.upsertTopic(it) }
        }
    }

    fun toMachineCommand(action: QuickActionEntity, beanProfile: String? = null): MachineCommand = when (action.kind) {
        QuickActionKind.MAKE_DRINK.name, QuickActionKind.CUSTOM.name -> MachineCommand.BrewDrink(
            drinkKey = action.actionKey,
            displayName = action.title,
            groundCoffeeGrams = action.groundCoffeeGrams,
            waterMl = action.waterMl,
            milkMl = action.milkMl,
            milkFoamMl = action.milkFoamMl,
            beanProfile = beanProfile,
        )
        else -> MachineCommand.MachineControl(
            commandKey = action.actionKey,
            displayName = action.title,
        )
    }

    // Favorite recipes are executed deterministically (see ChatViewModel.brewFavorite); they are
    // intentionally NOT converted into an LLM text command.

    companion object {
        fun defaultQuickActions(): List<QuickActionEntity> = listOf(
            drink("espresso", "Espresso", 0, 8, 30, 0, 0),
            drink("cappuccino", "Cappuccino", 1, 8, 30, 90, 30),
            drink("latte", "Latte", 2, 8, 30, 120, 15),
            drink("americano", "Americano", 3, 8, 50, 0, 0),
            drink("flat_white", "Flat White", 4, 10, 35, 100, 20),
            control("power_on", "Turn On", 10),
            control("power_off", "Turn Off", 11),
            control("rinse", "Rinse", 12),
            control("descale", "Descale", 13),
        )

        fun defaultHelpTopics(): List<SavedTopicEntity> = listOf(
            topic("operate", "Operate my machine", "Operate", "How do I operate my coffee machine step by step?", 0),
            topic("descale_help", "Descale & clean", "Service", "How do I descale and clean my coffee machine?", 1),
            topic("troubleshoot", "Troubleshooting", "Service", "My coffee machine has a problem. Help me troubleshoot.", 2),
            topic("brand", "Brand & warranty", "Brand", "Tell me about my coffee machine brand, warranty, and support.", 3),
            topic("shop", "Shop beans & accessories", "Shop", "What coffee beans and accessories do you recommend for my machine?", 4),
            topic("learn", "Coffee basics", "Learn", "Teach me coffee basics for better drinks at home.", 5),
        )

        private fun drink(
            key: String,
            title: String,
            order: Int,
            grams: Int,
            water: Int,
            milk: Int,
            foam: Int,
        ) = QuickActionEntity(
            id = "builtin_$key",
            title = title,
            kind = QuickActionKind.MAKE_DRINK.name,
            actionKey = key,
            groundCoffeeGrams = grams,
            waterMl = water,
            milkMl = milk,
            milkFoamMl = foam,
            enabled = false,
            sortOrder = order,
            isBuiltIn = true,
        )

        private fun control(key: String, title: String, order: Int) = QuickActionEntity(
            id = "builtin_$key",
            title = title,
            kind = QuickActionKind.MACHINE_CONTROL.name,
            actionKey = key,
            enabled = true,
            sortOrder = order,
            isBuiltIn = true,
        )

        private fun topic(id: String, title: String, category: String, prompt: String, order: Int) =
            SavedTopicEntity(
                id = id,
                title = title,
                category = category,
                prompt = prompt,
                isSaved = false,
                isBuiltIn = true,
                sortOrder = order,
            )

        fun newCustomDrinkAction(title: String, grams: Int, water: Int, milk: Int, foam: Int): QuickActionEntity {
            val id = UUID.randomUUID().toString()
            return QuickActionEntity(
                id = id,
                title = title,
                kind = QuickActionKind.CUSTOM.name,
                actionKey = "custom_$id",
                groundCoffeeGrams = grams,
                waterMl = water,
                milkMl = milk,
                milkFoamMl = foam,
                enabled = true,
                sortOrder = 100,
                isBuiltIn = false,
            )
        }

        fun newRecipe(
            name: String,
            groundCoffeeGrams: Int,
            waterMl: Int,
            milkMl: Int,
            milkFoamMl: Int,
            hasMilk: Boolean = true,
            shotCount: Int = 1,
            id: String = UUID.randomUUID().toString(),
        ): CoffeeRecipeEntity {
            val now = System.currentTimeMillis()
            return CoffeeRecipeEntity(
                id = id,
                name = name,
                groundCoffeeGrams = groundCoffeeGrams,
                waterMl = waterMl,
                milkMl = milkMl,
                milkFoamMl = milkFoamMl,
                createdAt = now,
                updatedAt = now,
                hasMilk = hasMilk,
                shotCount = shotCount,
            )
        }
    }
}
