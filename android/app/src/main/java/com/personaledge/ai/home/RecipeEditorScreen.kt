package com.personaledge.ai.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var groundCoffee by remember(existing) {
        mutableFloatStateOf((existing?.groundCoffeeGrams ?: 10).toFloat())
    }
    var water by remember(existing) {
        mutableFloatStateOf((existing?.waterMl ?: 30).toFloat())
    }
    var milk by remember(existing) {
        mutableFloatStateOf((existing?.milkMl ?: 90).toFloat())
    }
    var foam by remember(existing) {
        mutableFloatStateOf((existing?.milkFoamMl ?: 25).toFloat())
    }

    val canSave = name.isNotBlank()

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
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = CoffeeBrown)
            }
            Text(
                text = if (existing == null) "New Favorite Beverage" else "Edit Favorite Beverage",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = CoffeeText,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = Color(0xFFE8DDD0))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "Create your own coffee recipe with precise measurements for your machine.",
                fontSize = 14.sp,
                color = CoffeeText.copy(alpha = 0.6f),
            )

            ProfileLabeledField(label = "Beverage name") {
                CoffeeProfileTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "e.g. Morning Cappuccino",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            RecipeSlider(
                label = "Ground coffee",
                value = groundCoffee,
                range = RecipeRanges.GROUND_COFFEE_MIN.toFloat()..RecipeRanges.GROUND_COFFEE_MAX.toFloat(),
                unit = "g",
                onValueChange = { groundCoffee = it },
            )

            RecipeSlider(
                label = "Water through coffee",
                value = water,
                range = RecipeRanges.WATER_MIN.toFloat()..RecipeRanges.WATER_MAX.toFloat(),
                unit = "ml",
                onValueChange = { water = it },
                hint = "Water poured through the ground coffee cup",
            )

            RecipeSlider(
                label = "Milk",
                value = milk,
                range = RecipeRanges.MILK_MIN.toFloat()..RecipeRanges.MILK_MAX.toFloat(),
                unit = "ml",
                onValueChange = { milk = it },
            )

            RecipeSlider(
                label = "Milk foam",
                value = foam,
                range = RecipeRanges.FOAM_MIN.toFloat()..RecipeRanges.FOAM_MAX.toFloat(),
                unit = "ml",
                onValueChange = { foam = it },
            )
        }

        Button(
            onClick = {
                val recipe = if (existing != null) {
                    existing.copy(
                        name = name.trim(),
                        groundCoffeeGrams = groundCoffee.toInt(),
                        waterMl = water.toInt(),
                        milkMl = milk.toInt(),
                        milkFoamMl = foam.toInt(),
                        updatedAt = System.currentTimeMillis(),
                    )
                } else {
                    CoffeeActionStore.newRecipe(
                        name = name.trim(),
                        groundCoffeeGrams = groundCoffee.toInt(),
                        waterMl = water.toInt(),
                        milkMl = milk.toInt(),
                        milkFoamMl = foam.toInt(),
                    )
                }
                onSave(recipe)
            },
            enabled = canSave,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            colors = coffeePrimaryButtonColors(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = if (existing == null) "Save Favorite Beverage" else "Update Favorite Beverage",
                modifier = Modifier.padding(vertical = 4.dp),
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
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
