package com.personaledge.ai.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "memory_cache")
data class MemoryCacheEntity(
    @PrimaryKey val id: String,
    val content: String,
    val source: String,
    val timestamp: Long,
)

@Entity(tableName = "pending_sync")
data class PendingSyncEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val userMessage: String,
    val assistantMessage: String,
    val timestamp: Long,
)

@Entity(tableName = "cached_config")
data class CachedConfigEntity(
    @PrimaryKey val id: Int = 1,
    val systemPrompt: String,
    val personalityRules: String,
    val tone: String,
    val lastSyncedAt: Long,
)

@Dao
interface MemoryCacheDao {
    @Query("SELECT * FROM memory_cache ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMemories(limit: Int = 50): List<MemoryCacheEntity>

    @Query("SELECT content FROM memory_cache ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getMemoryTexts(limit: Int = 20): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemories(memories: List<MemoryCacheEntity>)

    @Query("DELETE FROM memory_cache")
    suspend fun clearMemories()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingSync(turn: PendingSyncEntity)

    @Query("SELECT * FROM pending_sync ORDER BY timestamp ASC")
    suspend fun getPendingSyncs(): List<PendingSyncEntity>

    @Query("DELETE FROM pending_sync WHERE id IN (:ids)")
    suspend fun deletePendingSyncs(ids: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveConfig(config: CachedConfigEntity)

    @Query("SELECT * FROM cached_config WHERE id = 1 LIMIT 1")
    suspend fun getConfig(): CachedConfigEntity?
}
