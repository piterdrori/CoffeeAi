package com.personaledge.ai.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personaledge.ai.coffee.CoffeeActionStore
import com.personaledge.ai.coffee.CoffeeRecipeEntity
import com.personaledge.ai.coffee.CoffeeRecipeLogic
import com.personaledge.ai.home.RecipeWizardLogic.RecipeControl
import com.personaledge.ai.home.RecipeWizardLogic.Step
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeCream
import com.personaledge.ai.ui.theme.CoffeeText

object RecipeRanges {
    const val GROUND_COFFEE_MIN = 6
    const val GROUND_COFFEE_MAX = 20
    const val WATER_MIN = 20
    const val WATER_MAX = 60
    const val MILK_MIN = 30
    const val MILK_MAX = 150
    const val FOAM_MIN = 10
    const val FOAM_MAX = 50
}

@Composable
fun RecipeEditorScreen(
    existing: CoffeeRecipeEntity? = null,
    onSave: (CoffeeRecipeEntity) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember(existing) { mutableStateOf(Step.NAME) }
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    // Editing loads the stored milk mode; a legacy row (no explicit flag) infers it from amounts.
    var hasMilk by remember(existing) {
        mutableStateOf(
            existing?.let { it.hasMilk || CoffeeRecipeLogic.inferHasMilk(it.milkMl, it.milkFoamMl) },
        )
    }
    var shotCount by remember(existing) {
        mutableStateOf(existing?.let { CoffeeRecipeLogic.normalizeShotCount(it.shotCount) })
    }
    var groundCoffee by remember(existing) { mutableFloatStateOf((existing?.groundCoffeeGrams ?: 10).toFloat()) }
    var water by remember(existing) { mutableFloatStateOf((existing?.waterMl ?: 30).toFloat()) }
    var milk by remember(existing) { mutableFloatStateOf((existing?.milkMl?.takeIf { it > 0 } ?: 90).toFloat()) }
    var foam by remember(existing) { mutableFloatStateOf((existing?.milkFoamMl?.takeIf { it > 0 } ?: 25).toFloat()) }

    val canAdvance = RecipeWizardLogic.canAdvance(step, name, hasMilk, shotCount)
    val isReview = step == Step.REVIEW

    fun buildRecipe(): CoffeeRecipeEntity {
        val hm = hasMilk ?: true
        val sc = CoffeeRecipeLogic.normalizeShotCount(shotCount ?: 1)
        val g = groundCoffee.toInt()
        val w = water.toInt()
        val m = CoffeeRecipeLogic.persistedMilkMl(hm, milk.toInt())
        val f = CoffeeRecipeLogic.persistedFoamMl(hm, foam.toInt())
        return existing?.copy(
            name = name.trim(),
            groundCoffeeGrams = g,
            waterMl = w,
            milkMl = m,
            milkFoamMl = f,
            hasMilk = hm,
            shotCount = sc,
            updatedAt = System.currentTimeMillis(),
        ) ?: CoffeeActionStore.newRecipe(
            name = name.trim(),
            groundCoffeeGrams = g,
            waterMl = w,
            milkMl = m,
            milkFoamMl = f,
            hasMilk = hm,
            shotCount = sc,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CoffeeCream)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (step == Step.NAME) onBack() else step = RecipeWizardLogic.previous(step) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = CoffeeBrown)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (existing == null) "New Favorite Beverage" else "Edit Favorite Beverage",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = CoffeeText,
                )
                Text(
                    text = "Step ${RecipeWizardLogic.stepNumber(step)} of ${RecipeWizardLogic.totalSteps} · ${stepTitle(step)}",
                    fontSize = 12.sp,
                    color = CoffeeText.copy(alpha = 0.55f),
                )
            }
        }
        HorizontalDivider(color = Color(0xFFE8DDD0))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when (step) {
                Step.NAME -> {
                    StepHeading("What's this beverage called?")
                    ProfileLabeledField(label = "Beverage name") {
                        CoffeeProfileTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = "e.g. Morning Cappuccino",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Step.MILK -> {
                    StepHeading("Milk choice")
                    ChoiceCard("With milk", "Adds milk and milk foam after coffee", hasMilk == true) { hasMilk = true }
                    ChoiceCard("Without milk", "Black coffee — no milk or foam", hasMilk == false) { hasMilk = false }
                }

                Step.SHOT -> {
                    StepHeading("Coffee shot")
                    ChoiceCard("Single shot", "One coffee extraction cycle", shotCount == 1) { shotCount = 1 }
                    ChoiceCard("Double shot", "Two coffee extraction cycles", shotCount == 2) { shotCount = 2 }
                }

                Step.RECIPE -> {
                    StepHeading("Recipe amounts")
                    val controls = RecipeWizardLogic.visibleControls(hasMilk ?: true)
                    controls.forEach { control ->
                        when (control) {
                            RecipeControl.GROUND_COFFEE -> RecipeSlider(
                                label = "Ground coffee",
                                value = groundCoffee,
                                range = RecipeRanges.GROUND_COFFEE_MIN.toFloat()..RecipeRanges.GROUND_COFFEE_MAX.toFloat(),
                                unit = "g",
                                onValueChange = { groundCoffee = it },
                            )
                            RecipeControl.WATER -> RecipeSlider(
                                label = "Water through coffee",
                                value = water,
                                range = RecipeRanges.WATER_MIN.toFloat()..RecipeRanges.WATER_MAX.toFloat(),
                                unit = "ml",
                                onValueChange = { water = it },
                                hint = "Water poured through the ground coffee cup",
                            )
                            RecipeControl.MILK -> RecipeSlider(
                                label = "Milk",
                                value = milk,
                                range = RecipeRanges.MILK_MIN.toFloat()..RecipeRanges.MILK_MAX.toFloat(),
                                unit = "ml",
                                onValueChange = { milk = it },
                            )
                            RecipeControl.MILK_FOAM -> RecipeSlider(
                                label = "Milk foam",
                                value = foam,
                                range = RecipeRanges.FOAM_MIN.toFloat()..RecipeRanges.FOAM_MAX.toFloat(),
                                unit = "ml",
                                onValueChange = { foam = it },
                            )
                        }
                    }
                }

                Step.REVIEW -> {
                    StepHeading("Review & save")
                    ReviewRow("Name", name.trim())
                    ReviewRow("Milk", if (hasMilk == true) "With milk" else "Without milk")
                    ReviewRow("Shot", if (shotCount == 2) "Double (two cycles)" else "Single (one cycle)")
                    ReviewRow("Ground coffee", "${groundCoffee.toInt()} g")
                    ReviewRow("Water through coffee", "${water.toInt()} ml")
                    if (hasMilk == true) {
                        ReviewRow("Milk", "${milk.toInt()} ml")
                        ReviewRow("Milk foam", "${foam.toInt()} ml")
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (step != Step.NAME) {
                OutlinedButton(
                    onClick = { step = RecipeWizardLogic.previous(step) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("Back", modifier = Modifier.padding(vertical = 4.dp), color = CoffeeBrown)
                }
            }
            Button(
                onClick = {
                    if (isReview) onSave(buildRecipe()) else step = RecipeWizardLogic.next(step)
                },
                enabled = canAdvance,
                modifier = Modifier.weight(1f),
                colors = coffeePrimaryButtonColors(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = if (isReview) {
                        if (existing == null) "Save Favorite" else "Update Favorite"
                    } else {
                        "Next"
                    },
                    modifier = Modifier.padding(vertical = 4.dp),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }
    }
}

private fun stepTitle(step: Step): String = when (step) {
    Step.NAME -> "Name"
    Step.MILK -> "Milk"
    Step.SHOT -> "Shot"
    Step.RECIPE -> "Recipe"
    Step.REVIEW -> "Review"
}

@Composable
private fun StepHeading(text: String) {
    Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = CoffeeText)
}

@Composable
private fun ChoiceCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) CoffeeBrown else CoffeeText.copy(alpha = 0.25f),
                shape = RoundedCornerShape(16.dp),
            )
            .background(
                color = if (selected) CoffeeBrown.copy(alpha = 0.08f) else Color.White,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = CoffeeText, fontSize = 16.sp)
            Text(subtitle, color = CoffeeText.copy(alpha = 0.55f), fontSize = 13.sp)
        }
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = CoffeeBrown)
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = CoffeeText.copy(alpha = 0.6f), fontSize = 14.sp)
        Text(value, color = CoffeeText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RecipeSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onValueChange: (Float) -> Unit,
    hint: String? = null,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, fontWeight = FontWeight.SemiBold, color = CoffeeText, fontSize = 15.sp)
                hint?.let {
                    Text(text = it, fontSize = 12.sp, color = CoffeeText.copy(alpha = 0.45f))
                }
            }
            Text(
                text = "${value.toInt()} $unit",
                fontWeight = FontWeight.Bold,
                color = CoffeeBrown,
                fontSize = 16.sp,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = (range.endInclusive - range.start).toInt() - 1,
            colors = SliderDefaults.colors(
                thumbColor = CoffeeBrown,
                activeTrackColor = CoffeeBrown,
                inactiveTrackColor = CoffeeCream,
            ),
        )
    }
}
