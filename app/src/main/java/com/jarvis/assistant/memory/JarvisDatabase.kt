package com.jarvis.assistant.memory

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.Date

// ─── Entidades ───────────────────────────────────────────────────────────────

@Entity(tableName = "commands")
data class CommandEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val command: String,
    val response: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val intent: String = ""
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String,
    val category: String = "general", // general, preference, fact
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false
)

// ─── DAOs ─────────────────────────────────────────────────────────────────────

@Dao
interface CommandDao {
    @Insert
    suspend fun insert(command: CommandEntity): Long

    @Query("SELECT * FROM commands ORDER BY timestamp DESC LIMIT 50")
    suspend fun getRecent(): List<CommandEntity>

    @Query("SELECT * FROM commands WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getSince(since: Long): List<CommandEntity>

    @Query("SELECT * FROM commands ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<CommandEntity>>

    @Query("DELETE FROM commands WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity)

    @Query("SELECT * FROM memories WHERE key = :key LIMIT 1")
    suspend fun getByKey(key: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE category = :category ORDER BY timestamp DESC")
    suspend fun getByCategory(category: String): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY timestamp DESC LIMIT 20")
    suspend fun getAll(): List<MemoryEntity>

    @Delete
    suspend fun delete(memory: MemoryEntity)
}

@Dao
interface NotificationDao {
    @Insert
    suspend fun insert(notification: NotificationEntity): Long

    @Query("SELECT * FROM notifications ORDER BY timestamp DESC LIMIT 20")
    suspend fun getRecent(): List<NotificationEntity>

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("DELETE FROM notifications WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}

// ─── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities = [CommandEntity::class, MemoryEntity::class, NotificationEntity::class],
    version = 1,
    exportSchema = false
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun commandDao(): CommandDao
    abstract fun memoryDao(): MemoryDao
    abstract fun notificationDao(): NotificationDao

    companion object {
        @Volatile private var INSTANCE: JarvisDatabase? = null

        fun getInstance(context: Context): JarvisDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    JarvisDatabase::class.java,
                    "jarvis_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
