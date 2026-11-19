package com.example.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local Room database storing conversation sessions, messages, and attachment metadata.
 */
@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        ChatAttachmentEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class CoarenaDatabase : RoomDatabase() {
    abstract fun chatHistoryDao(): ChatHistoryDao
}
