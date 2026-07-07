package com.personaledge.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeText

private val ChatBarHeight = 52.dp
private val ChatBarShape = RoundedCornerShape(26.dp)

@Composable
fun CoffeeChatComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onLetsTalk: () -> Unit,
    enabled: Boolean,
    canSend: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MessageInputBar(
            text = text,
            onTextChange = onTextChange,
            onSend = onSend,
            enabled = enabled,
            canSend = canSend,
        )
        LetsTalkBar(
            onClick = onLetsTalk,
            enabled = enabled,
        )
    }
}

@Composable
private fun MessageInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    canSend: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ChatBarHeight)
            .clip(ChatBarShape)
            .background(Color.White),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 15.sp,
                color = CoffeeText,
            ),
            cursorBrush = SolidColor(CoffeeBrown),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 18.dp),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier.fillMaxHeight(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = "Type your message... /",
                            fontSize = 15.sp,
                            color = CoffeeText.copy(alpha = 0.45f),
                        )
                    }
                    inner()
                }
            },
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp))
                .background(if (canSend) CoffeeBrown else CoffeeBrown.copy(alpha = 0.45f))
                .clickable(enabled = canSend, onClick = onSend)
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Send",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun LetsTalkBar(
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ChatBarHeight)
            .clip(ChatBarShape)
            .background(if (enabled) CoffeeBrown else CoffeeBrown.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = "Let's Talk",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
