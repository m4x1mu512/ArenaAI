package com.example.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for local chat sessions, messages, and image attachment metadata.
 */
@Dao
interface ChatHistoryDao {

    @Query(
        """
        SELECT
            s.id AS id,
            s.title AS title,
            s.model_id AS model_id,
            s.created_at_millis AS created_at_millis,
            s.updated_at_millis AS updated_at_millis,
            COUNT(m.id) AS message_count,
            (
                SELECT cm.content
                FROM chat_messages cm
                WHERE cm.session_id = s.id
                ORDER BY cm.timestamp_millis DESC
                LIMIT 1
            ) AS last_message_preview
        FROM chat_sessions s
        LEFT JOIN chat_messages m ON m.session_id = s.id
        GROUP BY s.id
        ORDER BY s.updated_at_millis DESC
        """,
    )
    fun observeSessions(): Flow<List<ChatSessionSummaryTuple>>

    @Query(
        """
        SELECT
            s.id AS id,
            s.title AS title,
            s.model_id AS model_id,
            s.created_at_millis AS created_at_millis,
            s.updated_at_millis AS updated_at_millis,
            COUNT(m.id) AS message_count,
            (
                SELECT cm.content
                FROM chat_messages cm
                WHERE cm.session_id = s.id
                ORDER BY cm.timestamp_millis DESC
                LIMIT 1
            ) AS last_message_preview
        FROM chat_sessions s
        LEFT JOIN chat_messages m ON m.session_id = s.id
        GROUP BY s.id
        ORDER BY s.updated_at_millis DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestSession(): ChatSessionSummaryTuple?

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp_millis ASC")
    fun observeMessages(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp_millis ASC")
    suspend fun getMessagesForSession(sessionId: String): List<ChatMessageEntity>

    @Transaction
    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp_millis ASC")
    fun observeMessagesWithAttachments(sessionId: String): Flow<List<ChatMessageWithAttachments>>

    @Transaction
    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp_millis ASC")
    suspend fun getMessagesWithAttachments(sessionId: String): List<ChatMessageWithAttachments>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: ChatSessionEntity)

    @Update
    suspend fun updateSession(session: ChatSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttachments(attachments: List<ChatAttachmentEntity>)

    @Query("SELECT * FROM chat_attachments WHERE message_id = :messageId")
    suspend fun getAttachmentsForMessage(messageId: String): List<ChatAttachmentEntity>

    @Query("DELETE FROM chat_attachments WHERE message_id = :messageId")
    suspend fun deleteAttachmentsForMessage(messageId: String)

    @Transaction
    suspend fun saveSessionAndMessage(
        sessionId: String,
        fallbackTitle: String,
        modelId: String?,
        updatedAtMillis: Long,
        message: ChatMessageEntity,
        attachments: List<ChatAttachmentEntity>,
    ) {
        val existing = getSessionById(sessionId)
        if (existing == null) {
            insertSession(
                ChatSessionEntity(
                    id = sessionId,
                    title = fallbackTitle,
                    modelId = modelId,
                    createdAtMillis = updatedAtMillis,
                    updatedAtMillis = updatedAtMillis,
                ),
            )
        } else {
            updateSession(
                existing.copy(
                    modelId = modelId ?: existing.modelId,
                    updatedAtMillis = updatedAtMillis,
                ),
            )
        }
        upsertMessage(message)
        deleteAttachmentsForMessage(message.id)
        if (attachments.isNotEmpty()) {
            upsertAttachments(attachments)
        }
    }

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: String)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAllSessions()
}
