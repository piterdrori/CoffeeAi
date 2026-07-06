package com.personaledge.ai.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personaledge.ai.chat.ChatSessionEntity
import com.personaledge.ai.ui.theme.CoffeeBrown
import com.personaledge.ai.ui.theme.CoffeeCream
import com.personaledge.ai.ui.theme.CoffeeText

internal val sessionAccentGradients = listOf(
    listOf(Color(0xFFFFB347), Color(0xFFFF8C42)),
    listOf(Color(0xFF7BC67E), Color(0xFF4CAF50)),
    listOf(Color(0xFF5EC6C6), Color(0xFF2E9E9E)),
    listOf(Color(0xFFB388FF), Color(0xFF7C4DFF)),
)

@Composable
fun SessionListScreen(
    sessions: List<ChatSessionEntity>,
    onOpenChat: (String) -> Unit,
    onRemoveChat: ((String) -> Unit)? = null,
    onToggleFavorite: ((String, Boolean) -> Unit)? = null,
    showSearch: Boolean = false,
    emptyTitle: String = "No chats yet",
    emptySubtitle: String = "Start a new conversation with CoffeeAI",
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(sessions, query) {
        if (query.isBlank()) sessions
        else sessions.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.preview.contains(query, ignoreCase = true)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CoffeeCream)
            .statusBarsPadding(),
    ) {
        if (showSearch) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                placeholder = { Text("Search chats") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = CoffeeBrown)
                },
                shape = RoundedCornerShape(24.dp),
                singleLine = true,
            )
            HorizontalDivider(color = Color(0xFFE8DDD0), thickness = 1.dp)
        }

        if (filtered.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(emptyTitle, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CoffeeText)
                Text(
                    emptySubtitle,
                    fontSize = 14.sp,
                    color = CoffeeText.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        onClick = { onOpenChat(session.id) },
                        onRemove = onRemoveChat?.let { remove -> { remove(session.id) } },
                        onToggleFavorite = onToggleFavorite,
                    )
                    HorizontalDivider(
                        color = Color(0xFFE8DDD0).copy(alpha = 0.7f),
                        thickness = 1.dp,
                        modifier = Modifier.padding(start = 72.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SessionRow(
    session: ChatSessionEntity,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onToggleFavorite: ((String, Boolean) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val gradient = sessionAccentGradients[session.accentIndex % sessionAccentGradients.size]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(gradient)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CoffeeText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.preview.isNotBlank()) {
                    Text(
                        text = session.preview,
                        fontSize = 13.sp,
                        color = CoffeeText.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        if (onToggleFavorite != null) {
            IconButton(onClick = { onToggleFavorite(session.id, !session.isFavorite) }) {
                Icon(
                    imageVector = if (session.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (session.isFavorite) "Remove favorite" else "Add favorite",
                    tint = CoffeeBrown,
                )
            }
        }
        if (onRemove != null) {
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = CoffeeText.copy(alpha = 0.5f),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Open") },
                        onClick = {
                            menuOpen = false
                            onClick()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun ChatsSearchScreen(
    onOpenChat: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    val sessions by viewModel.sessions.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.ensureSampleChats()
    }
    SessionListScreen(
        sessions = sessions,
        onOpenChat = onOpenChat,
        onRemoveChat = { viewModel.deleteSession(it) },
        showSearch = true,
        modifier = modifier,
    )
}
