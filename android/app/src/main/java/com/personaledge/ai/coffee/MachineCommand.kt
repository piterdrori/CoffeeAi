package com.personaledge.ai.coffee

enum class QuickActionKind {
    MAKE_DRINK,
    MACHINE_CONTROL,
    CUSTOM,
}

enum class BrewStatus {
    Idle,
    Preparing,
    Brewing,
    Ready,
    Error,
}

data class BrewState(
    val status: BrewStatus = BrewStatus.Idle,
    val displayName: String = "",
    val error: String? = null,
)

sealed class MachineCommand {
    abstract val displayName: String
    abstract fun toMessage(): String

    data class BrewDrink(
        val drinkKey: String,
        override val displayName: String,
        val groundCoffeeGrams: Int? = null,
        val waterMl: Int? = null,
        val milkMl: Int? = null,
        val milkFoamMl: Int? = null,
        val beanProfile: String? = null,
    ) : MachineCommand() {
        override fun toMessage(): String = buildBrewMessage(
            displayName = displayName,
            extraLines = buildList {
                add("drink: $drinkKey")
                groundCoffeeGrams?.let { add("ground_coffee_g: $it") }
                waterMl?.let { add("water_ml: $it") }
                milkMl?.let { add("milk_ml: $it") }
                milkFoamMl?.let { add("milk_foam_ml: $it") }
            },
            beanProfile = beanProfile,
        )
    }

    data class BrewRecipe(
        val recipeId: String,
        override val displayName: String,
        val groundCoffeeGrams: Int,
        val waterMl: Int,
        val milkMl: Int,
        val milkFoamMl: Int,
        val beanProfile: String? = null,
    ) : MachineCommand() {
        override fun toMessage(): String = buildBrewMessage(
            displayName = displayName,
            extraLines = buildList {
                add("recipe_id: $recipeId")
                add("ground_coffee_g: $groundCoffeeGrams")
                add("water_ml: $waterMl")
                add("milk_ml: $milkMl")
                add("milk_foam_ml: $milkFoamMl")
            },
            beanProfile = beanProfile,
        )
    }

    data class MachineControl(
        val commandKey: String,
        override val displayName: String,
    ) : MachineCommand() {
        override fun toMessage(): String = buildString {
            appendLine("[MACHINE_CONTROL]")
            appendLine("command: $commandKey")
            appendLine("display: $displayName")
            appendLine("---")
            append("Please execute this command on my coffee machine now.")
        }
    }
}

private fun buildBrewMessage(
    displayName: String,
    extraLines: List<String>,
    beanProfile: String?,
): String = buildString {
    appendLine("[MACHINE_BREW]")
    appendLine("display: $displayName")
    extraLines.forEach { appendLine(it) }
    if (!beanProfile.isNullOrBlank()) {
        appendLine(beanProfile)
    }
    appendLine("---")
    append(
        if (beanProfile.isNullOrBlank()) {
            "Please brew this drink on my coffee machine now."
        } else {
            "Please brew this drink on my coffee machine now. Adjust grind, dose, and extraction for the user's beans above."
        },
    )
}
