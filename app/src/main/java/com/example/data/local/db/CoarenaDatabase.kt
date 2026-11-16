package com.example.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local Room database storing conversation sessions and message history on device.
 */
@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class CoarenaDatabase : RoomDatabase() {
    abstract fun chatHistoryDao(): ChatHistoryDao
}
