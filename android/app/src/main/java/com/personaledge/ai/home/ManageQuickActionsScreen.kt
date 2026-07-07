package com.personaledge.ai.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personaledge.ai.coffee.QuickActionEntity
import com.personaledge.ai.coffee.QuickActionKind
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeCream
import com.personaledge.ai.ui.theme.CoffeeText

@Composable
fun ManageQuickActionsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QuickActionsViewModel = viewModel(),
) {
    val allActions by viewModel.allActions.collectAsState()
    var showAddControl by remember { mutableStateOf(false) }

    val controls = allActions.filter { it.kind == QuickActionKind.MACHINE_CONTROL.name }

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
                text = "Manage Machine Controls",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = CoffeeText,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = Color(0xFFE8DDD0))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = "Power, rinse, descale and other machine functions shown on Home.",
                    fontSize = 13.sp,
                    color = CoffeeText.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(controls, key = { it.id }) { action ->
                ActionRow(
                    action = action,
                    onToggle = { viewModel.toggleAction(action.id, it) },
                    onDelete = if (!action.isBuiltIn) ({ viewModel.deleteAction(action.id) }) else null,
                )
            }
            item {
                TextButton(onClick = { showAddControl = true }) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = CoffeeBrown)
                    Text("Add machine function", color = CoffeeBrown, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }

    if (showAddControl) {
        AddControlDialog(
            onDismiss = { showAddControl = false },
            onAdd = { title, key ->
                viewModel.addMachineControl(title, key)
                showAddControl = false
            },
        )
    }
}

@Composable
private fun ActionRow(
    action: QuickActionEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(action.title, fontWeight = FontWeight.SemiBold, color = CoffeeText, modifier = Modifier.weight(1f))
        Switch(
            checked = action.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = CoffeeBrown, checkedThumbColor = Color.White),
        )
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = CoffeeBrown.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun AddControlDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    val presets = listOf(
        "Warm up" to "warm_up",
        "Steam wand clean" to "steam_clean",
        "Empty drip tray" to "empty_tray",
    )
    var selectedKey by remember { mutableStateOf("custom") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add machine function") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Button label", color = CoffeeText) },
                    placeholder = { Text("e.g. Turn On", color = CoffeeText.copy(alpha = 0.55f)) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = CoffeeText, fontSize = 15.sp),
                    colors = coffeeFieldColors(),
                )
                presets.forEach { (label, key) ->
                    TextButton(onClick = { title = label; selectedKey = key }) {
                        Text(label, color = CoffeeBrown)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(title.trim(), selectedKey) },
                enabled = title.isNotBlank(),
                colors = coffeePrimaryButtonColors(),
            ) { Text("Add", color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
