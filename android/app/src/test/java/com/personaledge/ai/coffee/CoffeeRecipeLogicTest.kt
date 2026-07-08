package com.personaledge.ai.coffee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the favorite-beverage machine-execution rules: single/double coffee cycles,
 * with/without milk, and that milk/foam are applied once (never multiplied by the shot count).
 */
class CoffeeRecipeLogicTest {

    // Test 13 — Single shot = one coffee extraction cycle.
    @Test
    fun brewPlan_singleShotIsOneCycle() {
        val plan = CoffeeRecipeLogic.brewPlan(
            hasMilk = true, shotCount = 1, groundCoffeeGrams = 8, waterMl = 30, milkMl = 90, milkFoamMl = 25,
        )
        assertEquals(1, plan.coffeeCycles)
    }

    // Test 14 — Double shot = two coffee extraction cycles.
    @Test
    fun brewPlan_doubleShotIsTwoCycles() {
        val plan = CoffeeRecipeLogic.brewPlan(
            hasMilk = true, shotCount = 2, groundCoffeeGrams = 8, waterMl = 30, milkMl = 90, milkFoamMl = 25,
        )
        assertEquals(2, plan.coffeeCycles)
    }

    // Test 15 — milk and foam are NOT multiplied by shot count (applied once, final amounts).
    @Test
    fun brewPlan_milkAndFoamNotDoubled() {
        val single = CoffeeRecipeLogic.brewPlan(true, 1, 8, 30, 90, 25)
        val double = CoffeeRecipeLogic.brewPlan(true, 2, 8, 30, 90, 25)
        assertEquals(90, single.milkMl)
        assertEquals(25, single.milkFoamMl)
        // Same configured amounts regardless of double shot.
        assertEquals(90, double.milkMl)
        assertEquals(25, double.milkFoamMl)
    }

    // Test 8 — without milk excludes milk and foam from the plan.
    @Test
    fun brewPlan_withoutMilkExcludesMilk() {
        val plan = CoffeeRecipeLogic.brewPlan(
            hasMilk = false, shotCount = 1, groundCoffeeGrams = 8, waterMl = 50, milkMl = 90, milkFoamMl = 25,
        )
        assertFalse(plan.hasMilk)
        assertEquals(0, plan.milkMl)
        assertEquals(0, plan.milkFoamMl)
        // Coffee amounts still present.
        assertEquals(8, plan.groundCoffeeGramsPerCycle)
        assertEquals(50, plan.waterMlPerCycle)
    }

    @Test
    fun normalizeShotCount_clampsToSupportedRange() {
        assertEquals(1, CoffeeRecipeLogic.normalizeShotCount(0))
        assertEquals(1, CoffeeRecipeLogic.normalizeShotCount(1))
        assertEquals(2, CoffeeRecipeLogic.normalizeShotCount(2))
        assertEquals(2, CoffeeRecipeLogic.normalizeShotCount(5))
    }

    // Test 12 — legacy compatibility: hasMilk inferred from stored milk/foam amounts.
    @Test
    fun inferHasMilk_fromStoredAmounts() {
        assertTrue(CoffeeRecipeLogic.inferHasMilk(milkMl = 90, milkFoamMl = 0))
        assertTrue(CoffeeRecipeLogic.inferHasMilk(milkMl = 0, milkFoamMl = 20))
        assertFalse(CoffeeRecipeLogic.inferHasMilk(milkMl = 0, milkFoamMl = 0))
    }

    // Test 8 — persisted milk/foam reset to safe defaults for a without-milk recipe.
    @Test
    fun persistedMilk_resetsWhenNoMilk() {
        assertEquals(90, CoffeeRecipeLogic.persistedMilkMl(hasMilk = true, milkMl = 90))
        assertEquals(0, CoffeeRecipeLogic.persistedMilkMl(hasMilk = false, milkMl = 90))
        assertEquals(25, CoffeeRecipeLogic.persistedFoamMl(hasMilk = true, milkFoamMl = 25))
        assertEquals(0, CoffeeRecipeLogic.persistedFoamMl(hasMilk = false, milkFoamMl = 25))
    }

    // Deterministic, app-generated brew text reflects the plan (no LLM interpretation).
    @Test
    fun brewSummary_reflectsCyclesAndMilkDeterministically() {
        val doubleLatte = CoffeeRecipeLogic.brewPlan(true, 2, 8, 30, 90, 25)
        val summary = CoffeeRecipeLogic.brewSummary("Latte", doubleLatte)
        assertTrue(summary.contains("×2"))
        assertTrue(summary.contains("90ml milk"))
        assertTrue(summary.contains("25ml foam"))

        val singleEspresso = CoffeeRecipeLogic.brewPlan(false, 1, 8, 50, 90, 25)
        val espresso = CoffeeRecipeLogic.brewSummary("Espresso", singleEspresso)
        assertTrue(espresso.contains("×1"))
        assertTrue(espresso.contains("No milk stage"))
        assertFalse(espresso.contains("milk foam"))
    }

    @Test
    fun brewRequestLine_isDeterministic() {
        val plan = CoffeeRecipeLogic.brewPlan(true, 2, 8, 30, 90, 25)
        assertEquals("Brew Latte — Double shot, with milk", CoffeeRecipeLogic.brewRequestLine("Latte", plan))
        val noMilk = CoffeeRecipeLogic.brewPlan(false, 1, 8, 50, 0, 0)
        assertEquals("Brew Espresso — Single shot, no milk", CoffeeRecipeLogic.brewRequestLine("Espresso", noMilk))
    }
}
