package com.personaledge.ai.coffee

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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "coffee_recipes")
data class CoffeeRecipeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val groundCoffeeGrams: Int,
    val waterMl: Int,
    val milkMl: Int,
    val milkFoamMl: Int,
    val createdAt: Long,
    val updatedAt: Long,
    // Added in DB v2. Existing rows are migrated: hasMilk inferred from stored milk/foam, shots = 1.
    val hasMilk: Boolean = true,
    val shotCount: Int = 1,
) {
    val summary: String
        get() {
            val shots = if (shotCount >= 2) "Double" else "Single"
            val coffee = "$shots · ${groundCoffeeGrams}g · ${waterMl}ml water"
            return if (hasMilk) {
                "$coffee · ${milkMl}ml milk · ${milkFoamMl}ml foam"
            } else {
                "$coffee · no milk"
            }
        }
}

@Entity(tableName = "quick_actions")
data class QuickActionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val kind: String,
    val actionKey: String,
    val groundCoffeeGrams: Int? = null,
    val waterMl: Int? = null,
    val milkMl: Int? = null,
    val milkFoamMl: Int? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val isBuiltIn: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "saved_topics")
data class SavedTopicEntity(
    @PrimaryKey val id: String,
    val title: String,
    val category: String,
    val prompt: String,
    val isSaved: Boolean = false,
    val isBuiltIn: Boolean = true,
    val sortOrder: Int = 0,
)

@Dao
interface CoffeeActionDao {
    @Query("SELECT * FROM coffee_recipes ORDER BY updatedAt DESC")
    fun observeRecipes(): Flow<List<CoffeeRecipeEntity>>

    @Query("SELECT * FROM coffee_recipes WHERE id = :id LIMIT 1")
    suspend fun getRecipe(id: String): CoffeeRecipeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecipe(recipe: CoffeeRecipeEntity)

    @Query("DELETE FROM coffee_recipes WHERE id = :id")
    suspend fun deleteRecipe(id: String)

    @Query("SELECT * FROM quick_actions WHERE enabled = 1 ORDER BY sortOrder ASC")
    fun observeEnabledQuickActions(): Flow<List<QuickActionEntity>>

    @Query("SELECT * FROM quick_actions ORDER BY sortOrder ASC")
    fun observeAllQuickActions(): Flow<List<QuickActionEntity>>

    @Query("SELECT COUNT(*) FROM quick_actions")
    suspend fun quickActionCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQuickAction(action: QuickActionEntity)

    @Query("DELETE FROM quick_actions WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteCustomQuickAction(id: String)

    @Query("SELECT * FROM saved_topics WHERE isSaved = 1 ORDER BY sortOrder ASC")
    fun observeSavedTopics(): Flow<List<SavedTopicEntity>>

    @Query("SELECT * FROM saved_topics ORDER BY sortOrder ASC")
    fun observeAllTopics(): Flow<List<SavedTopicEntity>>

    @Query("SELECT COUNT(*) FROM saved_topics")
    suspend fun topicCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTopic(topic: SavedTopicEntity)
}

@Database(
    entities = [CoffeeRecipeEntity::class, QuickActionEntity::class, SavedTopicEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class CoffeeActionDatabase : RoomDatabase() {
    abstract fun coffeeActionDao(): CoffeeActionDao

    companion object {
        @Volatile
        private var instance: CoffeeActionDatabase? = null

        /**
         * v1 → v2: add the favorite-beverage milk-mode and shot-count columns. Non-destructive:
         * existing recipes are preserved, hasMilk is inferred from stored milk/foam amounts, and
         * shotCount defaults to Single(1).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE coffee_recipes ADD COLUMN hasMilk INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE coffee_recipes ADD COLUMN shotCount INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE coffee_recipes SET hasMilk = 0 WHERE milkMl = 0 AND milkFoamMl = 0")
            }
        }

        fun getInstance(context: Context): CoffeeActionDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CoffeeActionDatabase::class.java,
                    "coffeeai_actions.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
        }
    }
}
