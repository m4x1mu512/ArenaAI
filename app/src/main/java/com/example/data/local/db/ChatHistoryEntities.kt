package com.example.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

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
 * Metadata for an image stored in the app-private files directory.
 * Image bytes are intentionally kept outside Room.
 */
@Entity(
    tableName = "chat_attachments",
    foreignKeys = [
        ForeignKey(
            entity = ChatMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["message_id"])],
)
data class ChatAttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "local_path") val localPath: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "file_name") val fileName: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    @ColumnInfo(name = "attachment_kind") val attachmentKind: String,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
)

/** Message row with its related image metadata loaded in one Room transaction. */
data class ChatMessageWithAttachments(
    @Embedded val message: ChatMessageEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "message_id",
    )
    val attachments: List<ChatAttachmentEntity>,
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
