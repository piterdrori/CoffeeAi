package com.personaledge.ai.sync

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [MemoryCacheEntity::class, PendingSyncEntity::class, CachedConfigEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun memoryCacheDao(): MemoryCacheDao

    companion object {
        @Volatile
        private var instance: MemoryDatabase? = null

        fun getInstance(context: Context): MemoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "edge_ai_memory.db",
                ).build().also { instance = it }
            }
        }
    }
}
