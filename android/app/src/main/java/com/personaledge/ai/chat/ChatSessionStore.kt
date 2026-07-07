package com.personaledge.ai.chat

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val preview: String,
    val accentIndex: Int,
    val updatedAt: Long,
    val isFavorite: Boolean = false,
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String,
    val content: String,
    val imageUri: String? = null,
    val createdAt: Long,
)

@Dao
interface ChatSessionDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query(
        """
        SELECT s.* FROM chat_sessions s
        WHERE EXISTS (SELECT 1 FROM chat_messages m WHERE m.sessionId = s.id)
        ORDER BY s.updatedAt DESC
        """,
    )
    fun observeSessionsWithMessages(): Flow<List<ChatSessionEntity>>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun messageCount(sessionId: String): Int

    @Query(
        """
        SELECT s.* FROM chat_sessions s
        WHERE NOT EXISTS (SELECT 1 FROM chat_messages m WHERE m.sessionId = s.id)
        ORDER BY s.updatedAt DESC
        LIMIT 1
        """,
    )
    suspend fun findDraftSession(): ChatSessionEntity?

    @Query(
        """
        DELETE FROM chat_sessions
        WHERE id NOT IN (SELECT DISTINCT sessionId FROM chat_messages)
        """,
    )
    suspend fun deleteSessionsWithoutMessages()

    @Query("SELECT * FROM chat_sessions WHERE isFavorite = 1 ORDER BY updatedAt DESC")
    fun observeFavorites(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE id = :id LIMIT 1")
    suspend fun getSession(id: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun messagesFor(sessionId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ChatSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAllSessions()
}

@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatSessionDao(): ChatSessionDao

    companion object {
        @Volatile
        private var instance: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "coffeeai_chats.db",
                ).build().also { instance = it }
            }
        }
    }
}

class ChatSessionStore(context: Context) {
    private val dao = ChatDatabase.getInstance(context).chatSessionDao()

    val sessions: Flow<List<ChatSessionEntity>> = dao.observeSessions()
    val sessionsWithMessages: Flow<List<ChatSessionEntity>> = dao.observeSessionsWithMessages()
    val favorites: Flow<List<ChatSessionEntity>> = dao.observeFavorites()

    suspend fun messagesFor(sessionId: String): List<ChatMessageEntity> =
        dao.messagesFor(sessionId)

    suspend fun getSession(id: String): ChatSessionEntity? = dao.getSession(id)

    suspend fun findDraftSession(): ChatSessionEntity? = dao.findDraftSession()

    suspend fun deleteSessionsWithoutMessages() {
        dao.deleteSessionsWithoutMessages()
    }

    suspend fun hasMessages(sessionId: String): Boolean = dao.messageCount(sessionId) > 0

    suspend fun createSession(id: String, title: String, preview: String, accentIndex: Int) {
        dao.upsertSession(
            ChatSessionEntity(
                id = id,
                title = title,
                preview = preview,
                accentIndex = accentIndex,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun updateSessionMeta(id: String, title: String, preview: String) {
        val existing = dao.getSession(id)
        val accent = existing?.accentIndex ?: id.hashCode().mod(4).let { if (it < 0) -it else it }
        dao.upsertSession(
            ChatSessionEntity(
                id = id,
                title = title.ifBlank { "New Chat" },
                preview = preview,
                accentIndex = accent,
                updatedAt = System.currentTimeMillis(),
                isFavorite = existing?.isFavorite ?: false,
            ),
        )
    }

    suspend fun setFavorite(sessionId: String, favorite: Boolean) {
        val existing = dao.getSession(sessionId) ?: return
        dao.upsertSession(existing.copy(isFavorite = favorite, updatedAt = System.currentTimeMillis()))
    }

    suspend fun saveMessages(sessionId: String, messages: List<UiMessage>) {
        dao.deleteMessages(sessionId)
        if (messages.isEmpty()) return
        dao.upsertMessages(
            messages.mapIndexed { index, msg ->
                ChatMessageEntity(
                    sessionId = sessionId,
                    role = msg.role,
                    content = msg.content,
                    imageUri = msg.imageUri,
                    createdAt = System.currentTimeMillis() + index,
                )
            },
        )
    }

    suspend fun deleteSession(sessionId: String) {
        dao.deleteMessages(sessionId)
        dao.deleteSession(sessionId)
    }

    suspend fun clearAllSessions() {
        dao.deleteAllMessages()
        dao.deleteAllSessions()
    }
}
