package com.personaledge.ai.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personaledge.ai.chat.ChatSessionEntity
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeCream
import com.personaledge.ai.ui.theme.CoffeeText

private data class FavoriteShortcut(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val action: FavoriteShortcutAction,
)

private sealed interface FavoriteShortcutAction {
    data class Prompt(val text: String) : FavoriteShortcutAction
    data object Voice : FavoriteShortcutAction
}

private val coffeeShortcuts = listOf(
    FavoriteShortcut(
        title = "Brewing Tips",
        subtitle = "Grind size, water temp & pour-over basics",
        icon = Icons.Default.LocalCafe,
        action = FavoriteShortcutAction.Prompt("What are your best brewing tips for great coffee?"),
    ),
    FavoriteShortcut(
        title = "Coffee Recipes",
        subtitle = "Espresso, cold brew & specialty drinks",
        icon = Icons.Default.LocalCafe,
        action = FavoriteShortcutAction.Prompt("Can you suggest a strong coffee recipe?"),
    ),
    FavoriteShortcut(
        title = "Morning Routine",
        subtitle = "Plan your perfect start to the day",
        icon = Icons.Default.WbSunny,
        action = FavoriteShortcutAction.Prompt("Help me plan my morning coffee routine."),
    ),
    FavoriteShortcut(
        title = "Let's Talk",
        subtitle = "Voice chat with CoffeeAI offline",
        icon = Icons.Default.Mic,
        action = FavoriteShortcutAction.Voice,
    ),
)

@Composable
fun FavoritesScreen(
    onOpenChat: (String) -> Unit,
    onQuickAction: (String) -> Unit,
    onLetsTalk: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel = viewModel(),
) {
    val favorites by viewModel.favorites.collectAsState()
    var editMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.ensureSampleFavorites()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CoffeeCream)
            .statusBarsPadding(),
    ) {
        FavoritesTopBar(
            editMode = editMode,
            onToggleEdit = { editMode = !editMode },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (favorites.isEmpty()) {
                item { EmptyFavoritesHint() }
            }

            if (favorites.isNotEmpty()) {
                item {
                    Text(
                        text = "Pinned Chats",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CoffeeText.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
                items(favorites, key = { it.id }) { session ->
                    PinnedChatCard(
                        session = session,
                        editMode = editMode,
                        onOpen = { onOpenChat(session.id) },
                        onUnpin = { viewModel.setFavorite(session.id, false) },
                        onDelete = { viewModel.deleteSession(session.id) },
                    )
                }
            }

            item {
                Text(
                    text = if (favorites.isEmpty()) "Quick Starts" else "Quick Starts",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CoffeeText.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp, top = if (favorites.isEmpty()) 4.dp else 4.dp),
                )
            }

            items(coffeeShortcuts, key = { it.title }) { shortcut ->
                ShortcutCard(
                    shortcut = shortcut,
                    onClick = {
                        when (val action = shortcut.action) {
                            is FavoriteShortcutAction.Prompt -> onQuickAction(action.text)
                            FavoriteShortcutAction.Voice -> onLetsTalk()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FavoritesTopBar(
    editMode: Boolean,
    onToggleEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.size(48.dp))
        Text(
            text = "Favorites",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = CoffeeText,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        TextButton(onClick = onToggleEdit) {
            Text(
                text = if (editMode) "Done" else "Edit",
                color = CoffeeBrown,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        }
    }
    HorizontalDivider(color = Color(0xFFE8DDD0), thickness = 1.dp)
}

@Composable
private fun FavoriteCardShell(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(CoffeeBrown),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = CoffeeText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = CoffeeText.copy(alpha = 0.55f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun PinnedChatCard(
    session: ChatSessionEntity,
    editMode: Boolean,
    onOpen: () -> Unit,
    onUnpin: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    FavoriteCardShell(
        icon = Icons.Default.ChatBubbleOutline,
        title = session.title,
        subtitle = session.preview.ifBlank { "Saved conversation" },
        onClick = onOpen,
        trailing = {
            if (editMode) {
                IconButton(
                    onClick = onUnpin,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Unpin chat",
                        tint = CoffeeBrown,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreHoriz,
                            contentDescription = "More options",
                            tint = CoffeeText.copy(alpha = 0.45f),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Unpin") },
                            onClick = {
                                menuOpen = false
                                onUnpin()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete chat") },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun ShortcutCard(
    shortcut: FavoriteShortcut,
    onClick: () -> Unit,
) {
    FavoriteCardShell(
        icon = shortcut.icon,
        title = shortcut.title,
        subtitle = shortcut.subtitle,
        onClick = onClick,
        trailing = {
            Icon(
                imageVector = Icons.Default.MoreHoriz,
                contentDescription = null,
                tint = CoffeeText.copy(alpha = 0.3f),
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(20.dp),
            )
        },
    )
}

@Composable
private fun EmptyFavoritesHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No pinned chats yet",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = CoffeeText.copy(alpha = 0.7f),
        )
        Text(
            text = "Star a conversation from Chats, or try a quick start below",
            fontSize = 13.sp,
            color = CoffeeText.copy(alpha = 0.45f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
