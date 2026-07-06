package com.personaledge.ai.home

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personaledge.ai.EdgeAiApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MemorySyncState {
    Synced,
    Syncing,
    Pending,
    Disconnected,
}

data class ProfileStats(
    val chatCount: Int = 0,
    val pinnedCount: Int = 0,
    val memorySyncState: MemorySyncState = MemorySyncState.Disconnected,
    val memorySyncLabel: String = "Checking…",
)

data class ProfileUiState(
    val profile: UserProfile = UserProfile(),
    val stats: ProfileStats = ProfileStats(),
    val supportMessage: String? = null,
    val supportSending: Boolean = false,
)

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EdgeAiApplication
    private val profileStore = ProfileStore(application)
    private val chatStore = app.chatSessionStore
    private val syncClient = app.syncClient
    private val memoryDao = app.memoryDatabase.memoryCacheDao()

    private val syncStats = MutableStateFlow(
        ProfileStats(memorySyncState = MemorySyncState.Syncing, memorySyncLabel = "Syncing…"),
    )
    private val supportUi = MutableStateFlow(Pair<String?, Boolean>(null, false))

    val uiState: StateFlow<ProfileUiState> = combine(
        profileStore.profile,
        chatStore.sessions,
        chatStore.favorites,
        syncStats,
        supportUi,
    ) { profile, sessions, favorites, sync, support ->
        ProfileUiState(
            profile = profile,
            stats = ProfileStats(
                chatCount = sessions.size,
                pinnedCount = favorites.size,
                memorySyncState = sync.memorySyncState,
                memorySyncLabel = sync.memorySyncLabel,
            ),
            supportMessage = support.first,
            supportSending = support.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    init {
        viewModelScope.launch {
            syncMemory(auto = true)
        }
    }

    fun savePersonalDetails(displayName: String, email: String, avatarPath: String) {
        viewModelScope.launch {
            profileStore.savePersonalDetails(displayName, email, avatarPath)
        }
    }

    fun saveCoffeePreferences(preferences: CoffeePreferences) {
        viewModelScope.launch {
            profileStore.saveCoffeePreferences(preferences)
        }
    }

    fun persistAvatarUri(context: Context, uri: Uri): String? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val file = File(context.filesDir, "profile_avatar.jpg")
            file.outputStream().use { output -> input.copyTo(output) }
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun syncMemory(auto: Boolean = false) {
        viewModelScope.launch {
            syncStats.update {
                it.copy(memorySyncState = MemorySyncState.Syncing, memorySyncLabel = "Syncing…")
            }
            try {
                syncClient.pullFullSync()
                syncClient.pushPendingTurns()
                refreshSyncStats()
            } catch (_: Exception) {
                val pending = memoryDao.getPendingSyncs().size
                val config = memoryDao.getConfig()
                val label = if (pending > 0) {
                    "$pending update${if (pending == 1) "" else "s"} pending"
                } else if (config != null) {
                    formatLastSync(config.lastSyncedAt)
                } else if (auto) {
                    "Tap to sync memory"
                } else {
                    "Could not reach memory"
                }
                syncStats.update {
                    it.copy(
                        memorySyncState = if (pending > 0) MemorySyncState.Pending else MemorySyncState.Disconnected,
                        memorySyncLabel = label,
                    )
                }
            }
        }
    }

    fun sendTeamMessage(message: String) {
        if (message.isBlank()) return
        viewModelScope.launch {
            supportUi.update { it.copy(second = true) }
            val profile = uiState.value.profile
            val result = syncClient.sendSupportMessage(
                name = profile.displayName,
                email = profile.email,
                message = message.trim(),
            )
            supportUi.update {
                it.copy(
                    first = if (result) "Message sent to the CoffeeAI team." else "Could not send message. Try again when you're online.",
                    second = false,
                )
            }
        }
    }

    fun clearSupportMessage() {
        supportUi.update { it.copy(first = null) }
    }

    private suspend fun refreshSyncStats() {
        val pending = memoryDao.getPendingSyncs().size
        val config = memoryDao.getConfig()
        val memories = memoryDao.getRecentMemories(500)
        val state = when {
            pending > 0 -> MemorySyncState.Pending
            config != null && memories.isNotEmpty() -> MemorySyncState.Synced
            config != null -> MemorySyncState.Synced
            else -> MemorySyncState.Disconnected
        }
        val label = when {
            pending > 0 -> "$pending pending"
            config != null -> formatLastSync(config.lastSyncedAt)
            else -> "Synced"
        }
        syncStats.update {
            it.copy(memorySyncState = state, memorySyncLabel = label)
        }
    }

    private fun formatLastSync(timestamp: Long): String {
        val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        return fmt.format(Date(timestamp))
    }
}
