package com.personaledge.ai.home

/**
 * Pure, Android-free rules for the guided favorite-beverage wizard so step validation and the
 * per-step visible controls can be unit tested without Compose.
 */
object RecipeWizardLogic {
    /** Ordered wizard steps. */
    enum class Step { NAME, MILK, SHOT, RECIPE, REVIEW }

    /** Recipe controls shown in the RECIPE step. */
    enum class RecipeControl { GROUND_COFFEE, WATER, MILK, MILK_FOAM }

    val orderedSteps: List<Step> = Step.entries

    /** With milk shows all four controls; without milk shows only coffee + water. */
    fun visibleControls(hasMilk: Boolean): List<RecipeControl> =
        if (hasMilk) {
            listOf(RecipeControl.GROUND_COFFEE, RecipeControl.WATER, RecipeControl.MILK, RecipeControl.MILK_FOAM)
        } else {
            listOf(RecipeControl.GROUND_COFFEE, RecipeControl.WATER)
        }

    /** Whether the user may proceed from [step] with the current selections. */
    fun canAdvance(step: Step, name: String, hasMilk: Boolean?, shotCount: Int?): Boolean = when (step) {
        Step.NAME -> name.isNotBlank()
        Step.MILK -> hasMilk != null
        Step.SHOT -> shotCount != null
        Step.RECIPE -> hasMilk != null // controls always valid once milk mode is chosen
        Step.REVIEW -> name.isNotBlank() && hasMilk != null && shotCount != null
    }

    fun next(step: Step): Step {
        val i = orderedSteps.indexOf(step)
        return orderedSteps.getOrElse(i + 1) { step }
    }

    fun previous(step: Step): Step {
        val i = orderedSteps.indexOf(step)
        return if (i <= 0) step else orderedSteps[i - 1]
    }

    fun stepNumber(step: Step): Int = orderedSteps.indexOf(step) + 1
    val totalSteps: Int get() = orderedSteps.size
}
