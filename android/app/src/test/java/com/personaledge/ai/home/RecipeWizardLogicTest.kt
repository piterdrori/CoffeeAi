package com.personaledge.ai.home

import com.personaledge.ai.home.RecipeWizardLogic.RecipeControl
import com.personaledge.ai.home.RecipeWizardLogic.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the guided favorite-beverage wizard rules: per-step validation and which recipe
 * controls are visible for with/without-milk.
 */
class RecipeWizardLogicTest {

    // Test 1 — name is required before leaving the NAME step.
    @Test
    fun canAdvance_nameRequired() {
        assertFalse(RecipeWizardLogic.canAdvance(Step.NAME, name = "", hasMilk = null, shotCount = null))
        assertFalse(RecipeWizardLogic.canAdvance(Step.NAME, name = "   ", hasMilk = null, shotCount = null))
        assertTrue(RecipeWizardLogic.canAdvance(Step.NAME, name = "Latte", hasMilk = null, shotCount = null))
    }

    // Test 2/3 — milk choice must be made to advance.
    @Test
    fun canAdvance_milkChoiceRequired() {
        assertFalse(RecipeWizardLogic.canAdvance(Step.MILK, name = "Latte", hasMilk = null, shotCount = null))
        assertTrue(RecipeWizardLogic.canAdvance(Step.MILK, name = "Latte", hasMilk = true, shotCount = null))
        assertTrue(RecipeWizardLogic.canAdvance(Step.MILK, name = "Latte", hasMilk = false, shotCount = null))
    }

    // Test 4/5 — shot choice must be made to advance.
    @Test
    fun canAdvance_shotChoiceRequired() {
        assertFalse(RecipeWizardLogic.canAdvance(Step.SHOT, name = "Latte", hasMilk = true, shotCount = null))
        assertTrue(RecipeWizardLogic.canAdvance(Step.SHOT, name = "Latte", hasMilk = true, shotCount = 1))
        assertTrue(RecipeWizardLogic.canAdvance(Step.SHOT, name = "Latte", hasMilk = true, shotCount = 2))
    }

    // Test 6 — with milk shows four controls.
    @Test
    fun visibleControls_withMilkShowsFour() {
        val controls = RecipeWizardLogic.visibleControls(hasMilk = true)
        assertEquals(
            listOf(RecipeControl.GROUND_COFFEE, RecipeControl.WATER, RecipeControl.MILK, RecipeControl.MILK_FOAM),
            controls,
        )
    }

    // Test 7 — without milk shows only coffee + water.
    @Test
    fun visibleControls_withoutMilkShowsTwo() {
        val controls = RecipeWizardLogic.visibleControls(hasMilk = false)
        assertEquals(listOf(RecipeControl.GROUND_COFFEE, RecipeControl.WATER), controls)
    }

    // Test 9 — Back/Next move through the ordered steps and preserve position.
    @Test
    fun nextAndPrevious_traverseSteps() {
        assertEquals(Step.MILK, RecipeWizardLogic.next(Step.NAME))
        assertEquals(Step.SHOT, RecipeWizardLogic.next(Step.MILK))
        assertEquals(Step.RECIPE, RecipeWizardLogic.next(Step.SHOT))
        assertEquals(Step.REVIEW, RecipeWizardLogic.next(Step.RECIPE))
        // Terminal step stays.
        assertEquals(Step.REVIEW, RecipeWizardLogic.next(Step.REVIEW))
        assertEquals(Step.RECIPE, RecipeWizardLogic.previous(Step.REVIEW))
        assertEquals(Step.NAME, RecipeWizardLogic.previous(Step.NAME))
    }

    @Test
    fun stepNumbering() {
        assertEquals(1, RecipeWizardLogic.stepNumber(Step.NAME))
        assertEquals(5, RecipeWizardLogic.stepNumber(Step.REVIEW))
        assertEquals(5, RecipeWizardLogic.totalSteps)
    }
}
