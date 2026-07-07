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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personaledge.ai.chat.ChatSessionEntity
import com.personaledge.ai.coffee.CoffeeRecipeEntity
import com.personaledge.ai.coffee.SavedTopicEntity
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeCream
import com.personaledge.ai.ui.theme.CoffeeText

@Composable
fun FavoritesScreen(
    onOpenChat: (String) -> Unit,
    onBrewRecipe: (CoffeeRecipeEntity) -> Unit,
    onHelpTopic: (String) -> Unit,
    onAddRecipe: () -> Unit,
    onEditRecipe: (CoffeeRecipeEntity) -> Unit,
    onBrowseHelp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel = viewModel(),
) {
    val favorites by viewModel.favorites.collectAsState()
    val recipes by viewModel.recipes.collectAsState()
    val savedTopics by viewModel.savedTopics.collectAsState()
    var editMode by remember { mutableStateOf(false) }

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
            item {
                SectionHeader(
                    title = "Your beverages",
                    action = {
                        IconButton(onClick = onAddRecipe) {
                            Icon(Icons.Default.Add, contentDescription = "Add recipe", tint = CoffeeBrown)
                        }
                    },
                )
            }

            if (recipes.isEmpty()) {
                item { EmptyRecipesHint(onAdd = onAddRecipe) }
            } else {
                items(recipes, key = { it.id }) { recipe ->
                    RecipeCard(
                        recipe = recipe,
                        editMode = editMode,
                        onBrew = { onBrewRecipe(recipe) },
                        onEdit = { onEditRecipe(recipe) },
                        onDelete = { viewModel.deleteRecipe(recipe.id) },
                    )
                }
            }

            item {
                SectionHeader(
                    title = "Saved Help",
                    modifier = androidx.compose.ui.Modifier.padding(top = 8.dp),
                    action = {
                        TextButton(onClick = onBrowseHelp) {
                            Text("Browse", color = CoffeeBrown, fontSize = 14.sp)
                        }
                    },
                )
            }

            if (savedTopics.isEmpty()) {
                item { EmptyHelpHint(onBrowse = onBrowseHelp) }
            } else {
                items(savedTopics, key = { it.id }) { topic ->
                    HelpTopicCard(
                        topic = topic,
                        onClick = { onHelpTopic(topic.prompt) },
                        onUnsave = { viewModel.toggleTopicSaved(topic, false) },
                    )
                }
            }

            if (favorites.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Pinned Chats",
                        modifier = androidx.compose.ui.Modifier.padding(top = 8.dp),
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
        }
    }
}

@Composable
fun HelpTopicsSheet(
    topics: List<SavedTopicEntity>,
    onToggleSave: (SavedTopicEntity, Boolean) -> Unit,
    onSelect: (SavedTopicEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(20.dp),
    ) {
        Text("Help topics", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = CoffeeText)
        Text(
            "Save guides for operating, servicing, or shopping for your machine.",
            fontSize = 13.sp,
            color = CoffeeText.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        topics.forEach { topic ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(topic) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(topic.title, fontWeight = FontWeight.SemiBold, color = CoffeeText)
                    Text(topic.category, fontSize = 12.sp, color = CoffeeText.copy(alpha = 0.45f))
                }
                IconButton(onClick = { onToggleSave(topic, !topic.isSaved) }) {
                    Icon(
                        Icons.Default.Bookmark,
                        contentDescription = if (topic.isSaved) "Unsave" else "Save",
                        tint = if (topic.isSaved) CoffeeBrown else CoffeeText.copy(alpha = 0.25f),
                    )
                }
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text("Done", color = CoffeeBrown)
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = CoffeeText.copy(alpha = 0.5f),
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, bottom = 2.dp),
        )
        action?.invoke()
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
            text = "Favorite Beverages",
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
            Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
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
private fun RecipeCard(
    recipe: CoffeeRecipeEntity,
    editMode: Boolean,
    onBrew: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    FavoriteCardShell(
        icon = Icons.Default.LocalCafe,
        title = recipe.name,
        subtitle = recipe.summary,
        onClick = onBrew,
        trailing = {
            if (editMode) {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Delete", tint = CoffeeBrown, modifier = Modifier.size(20.dp))
                }
            } else {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MoreHoriz, contentDescription = "More", tint = CoffeeText.copy(alpha = 0.45f))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
        },
    )
}

@Composable
private fun HelpTopicCard(
    topic: SavedTopicEntity,
    onClick: () -> Unit,
    onUnsave: () -> Unit,
) {
    FavoriteCardShell(
        icon = Icons.Default.MenuBook,
        title = topic.title,
        subtitle = topic.category,
        onClick = onClick,
        trailing = {
            IconButton(onClick = onUnsave, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Bookmark, contentDescription = "Unsave", tint = CoffeeBrown, modifier = Modifier.size(20.dp))
            }
        },
    )
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
                IconButton(onClick = onUnpin, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Unpin", tint = CoffeeBrown, modifier = Modifier.size(20.dp))
                }
            } else {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MoreHoriz, contentDescription = "More", tint = CoffeeText.copy(alpha = 0.45f))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Unpin") }, onClick = { menuOpen = false; onUnpin() })
                        DropdownMenuItem(text = { Text("Delete chat") }, onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
        },
    )
}

@Composable
private fun EmptyRecipesHint(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.7f))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No favorite beverages yet", fontWeight = FontWeight.SemiBold, color = CoffeeText.copy(alpha = 0.7f))
        Text(
            "Create a beverage here — it will appear on Home under Favorite Beverages.",
            fontSize = 13.sp,
            color = CoffeeText.copy(alpha = 0.45f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        TextButton(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = null, tint = CoffeeBrown)
            Text("Add favorite beverage", color = CoffeeBrown, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun EmptyHelpHint(onBrowse: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.7f))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No saved help topics", fontWeight = FontWeight.SemiBold, color = CoffeeText.copy(alpha = 0.7f))
        Text(
            "Save guides for operating, cleaning, or shopping for your machine.",
            fontSize = 13.sp,
            color = CoffeeText.copy(alpha = 0.45f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        TextButton(onClick = onBrowse) {
            Text("Browse topics", color = CoffeeBrown)
        }
    }
}
