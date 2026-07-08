package com.personaledge.ai.coffee

/**
 * Pure, Android-free rules for favorite-beverage recipes so the machine-execution behavior can be
 * unit tested without a device. Keeps the single/double-shot and with/without-milk semantics in one
 * place, shared by the recipe wizard, the deterministic brew executor, and tests.
 */
object CoffeeRecipeLogic {
    const val MIN_SHOTS = 1
    const val MAX_SHOTS = 2

    /** Clamp a stored/edited shot count to the supported Single(1)/Double(2) range. */
    fun normalizeShotCount(shots: Int): Int = shots.coerceIn(MIN_SHOTS, MAX_SHOTS)

    /**
     * Backward-compatible inference of the milk mode for legacy recipes saved before an explicit
     * [CoffeeRecipeEntity.hasMilk] flag existed: a recipe with any milk or foam was a milk drink.
     */
    fun inferHasMilk(milkMl: Int, milkFoamMl: Int): Boolean = milkMl > 0 || milkFoamMl > 0

    /**
     * The concrete plan the machine executes for a recipe.
     *
     * Rule (unless product logic says otherwise):
     *  - Single = one coffee extraction cycle; Double = two.
     *  - One cycle = ground coffee + water through coffee.
     *  - Milk and milk foam are the configured FINAL amounts and run ONCE after the coffee cycles —
     *    they are never multiplied by the shot count.
     *  - Without milk, milk and foam are excluded (zero).
     */
    data class BrewPlan(
        val coffeeCycles: Int,
        val hasMilk: Boolean,
        val groundCoffeeGramsPerCycle: Int,
        val waterMlPerCycle: Int,
        val milkMl: Int,
        val milkFoamMl: Int,
    )

    fun brewPlan(
        hasMilk: Boolean,
        shotCount: Int,
        groundCoffeeGrams: Int,
        waterMl: Int,
        milkMl: Int,
        milkFoamMl: Int,
    ): BrewPlan = BrewPlan(
        coffeeCycles = normalizeShotCount(shotCount),
        hasMilk = hasMilk,
        groundCoffeeGramsPerCycle = groundCoffeeGrams,
        waterMlPerCycle = waterMl,
        milkMl = if (hasMilk) milkMl else 0,
        milkFoamMl = if (hasMilk) milkFoamMl else 0,
    )

    /** Milk/foam that should actually be persisted — reset to safe defaults for a no-milk recipe. */
    fun persistedMilkMl(hasMilk: Boolean, milkMl: Int): Int = if (hasMilk) milkMl else 0
    fun persistedFoamMl(hasMilk: Boolean, milkFoamMl: Int): Int = if (hasMilk) milkFoamMl else 0

    fun shotLabel(coffeeCycles: Int): String = if (coffeeCycles >= 2) "Double" else "Single"

    /**
     * Deterministic, app-generated one-liner describing the brew request. NOT an LLM prompt — the
     * machine/execution layer derives everything from the structured [BrewPlan], not from this text.
     * This is only for user-facing display (e.g. the chat log entry for a brew).
     */
    fun brewRequestLine(name: String, plan: BrewPlan): String {
        val milk = if (plan.hasMilk) "with milk" else "no milk"
        return "Brew $name — ${shotLabel(plan.coffeeCycles)} shot, $milk"
    }

    /**
     * Deterministic, app-generated brew summary shown to the user after a favorite is brewed. Built
     * entirely from the structured [BrewPlan]; the LLM is never consulted to decide or interpret it.
     */
    fun brewSummary(name: String, plan: BrewPlan): String = buildString {
        append("$name is brewing.\n")
        append(
            "• Coffee extraction cycle ×${plan.coffeeCycles} " +
                "(${plan.groundCoffeeGramsPerCycle}g ground coffee, ${plan.waterMlPerCycle}ml water each)",
        )
        if (plan.hasMilk) {
            append("\n• Then ${plan.milkMl}ml milk and ${plan.milkFoamMl}ml foam once")
        } else {
            append("\n• No milk stage")
        }
    }
}
