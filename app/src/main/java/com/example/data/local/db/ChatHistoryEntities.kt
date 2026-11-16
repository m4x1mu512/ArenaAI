package com.example.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a saved local chat conversation.
 */
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "model_id") val modelId: String?,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
)

/**
 * Room entity representing a single persisted chat message inside a [ChatSessionEntity].
 */
@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["session_id"])],
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,
    val content: String,
    @ColumnInfo(name = "timestamp_millis") val timestampMillis: Long,
    @ColumnInfo(name = "model_id") val modelId: String?,
    @ColumnInfo(name = "is_error") val isError: Boolean,
    @ColumnInfo(name = "total_tokens") val totalTokens: Int?,
)

/**
 * Query projection combining session metadata with message count and latest snippet.
 */
data class ChatSessionSummaryTuple(
    val id: String,
    val title: String,
    @ColumnInfo(name = "model_id") val modelId: String?,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis") val updatedAtMillis: Long,
    @ColumnInfo(name = "message_count") val messageCount: Int,
    @ColumnInfo(name = "last_message_preview") val lastMessagePreview: String?,
)
