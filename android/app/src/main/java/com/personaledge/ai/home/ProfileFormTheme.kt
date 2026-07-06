package com.personaledge.ai.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeCream
import com.personaledge.ai.ui.theme.CoffeeText

private val ProfileFormColorScheme = lightColorScheme(
    primary = CoffeeBrown,
    onPrimary = Color.White,
    background = CoffeeCream,
    surface = Color.White,
    onSurface = CoffeeText,
    onSurfaceVariant = CoffeeText.copy(alpha = 0.72f),
    onBackground = CoffeeText,
    outline = CoffeeText.copy(alpha = 0.45f),
    surfaceVariant = Color(0xFFF5EDE4),
)

@Composable
fun ProfileFormTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ProfileFormColorScheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun profileFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = CoffeeText,
    unfocusedTextColor = CoffeeText,
    disabledTextColor = CoffeeText.copy(alpha = 0.55f),
    errorTextColor = CoffeeText,
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White,
    disabledContainerColor = Color.White,
    errorContainerColor = Color.White,
    cursorColor = CoffeeBrown,
    errorCursorColor = CoffeeBrown,
    focusedBorderColor = CoffeeBrown,
    unfocusedBorderColor = CoffeeText.copy(alpha = 0.45f),
    disabledBorderColor = CoffeeText.copy(alpha = 0.25f),
    errorBorderColor = CoffeeBrown,
    focusedLabelColor = CoffeeBrown,
    unfocusedLabelColor = CoffeeText,
    disabledLabelColor = CoffeeText.copy(alpha = 0.55f),
    errorLabelColor = CoffeeBrown,
    focusedPlaceholderColor = CoffeeText.copy(alpha = 0.55f),
    unfocusedPlaceholderColor = CoffeeText.copy(alpha = 0.55f),
    disabledPlaceholderColor = CoffeeText.copy(alpha = 0.4f),
    errorPlaceholderColor = CoffeeText.copy(alpha = 0.55f),
    focusedLeadingIconColor = CoffeeBrown,
    unfocusedLeadingIconColor = CoffeeText.copy(alpha = 0.6f),
    disabledLeadingIconColor = CoffeeText.copy(alpha = 0.4f),
    errorLeadingIconColor = CoffeeBrown,
    focusedTrailingIconColor = CoffeeBrown,
    unfocusedTrailingIconColor = CoffeeText.copy(alpha = 0.6f),
    disabledTrailingIconColor = CoffeeText.copy(alpha = 0.4f),
    errorTrailingIconColor = CoffeeBrown,
    focusedSupportingTextColor = CoffeeText.copy(alpha = 0.7f),
    unfocusedSupportingTextColor = CoffeeText.copy(alpha = 0.7f),
    disabledSupportingTextColor = CoffeeText.copy(alpha = 0.5f),
    errorSupportingTextColor = CoffeeText,
)

@Composable
fun profilePrimaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = CoffeeBrown,
    contentColor = Color.White,
    disabledContainerColor = CoffeeBrown.copy(alpha = 0.45f),
    disabledContentColor = Color.White,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun profileDropdownMenuColors() = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
    focusedTextColor = CoffeeText,
    unfocusedTextColor = CoffeeText,
    disabledTextColor = CoffeeText.copy(alpha = 0.55f),
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White,
    disabledContainerColor = Color.White,
    cursorColor = CoffeeBrown,
    focusedBorderColor = CoffeeBrown,
    unfocusedBorderColor = CoffeeText.copy(alpha = 0.45f),
    disabledBorderColor = CoffeeText.copy(alpha = 0.25f),
    focusedLabelColor = CoffeeBrown,
    unfocusedLabelColor = CoffeeText,
    disabledLabelColor = CoffeeText.copy(alpha = 0.55f),
    focusedTrailingIconColor = CoffeeBrown,
    unfocusedTrailingIconColor = CoffeeText.copy(alpha = 0.6f),
    disabledTrailingIconColor = CoffeeText.copy(alpha = 0.4f),
)

@Composable
internal fun ProfileLabeledField(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = CoffeeText,
        )
        content()
    }
}

@Composable
fun CoffeeProfileTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        textStyle = TextStyle(
            fontSize = 15.sp,
            color = CoffeeText,
        ),
        cursorBrush = SolidColor(CoffeeBrown),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, CoffeeText.copy(alpha = 0.45f), shape)
            .background(Color.White, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder.isNotBlank()) {
                    Text(
                        text = placeholder,
                        fontSize = 15.sp,
                        color = CoffeeText.copy(alpha = 0.55f),
                    )
                }
                inner()
            }
        },
    )
}
