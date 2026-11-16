package com.example.domain.repository

import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatSession
import kotlinx.coroutines.flow.Flow

/**
 * Persists and retrieves local chat conversations and messages on device.
 */
interface ChatHistoryRepository {
    /** Emits saved conversation sessions ordered by most recently updated first. */
    fun observeSessions(): Flow<List<ChatSession>>

    /** Emits persisted messages for [sessionId] in chronological order. */
    fun observeMessages(sessionId: String): Flow<List<ChatMessage>>

    /** Returns the most recently updated conversation, or null if history is empty. */
    suspend fun getLatestSession(): ChatSession?

    /** Returns all persisted messages for [sessionId] in chronological order. */
    suspend fun getMessages(sessionId: String): List<ChatMessage>

    /** Ensures the session exists (updating its timestamp/model) and inserts or updates [message]. */
    suspend fun saveMessage(
        sessionId: String,
        sessionTitle: String,
        modelId: String?,
        message: ChatMessage,
    )

    /** Deletes a single message by [messageId]. */
    suspend fun deleteMessage(messageId: String)

    /** Deletes an entire conversation and all of its messages. */
    suspend fun deleteSession(sessionId: String)

    /** Deletes all saved conversations and messages from local storage. */
    suspend fun clearAllHistory()
}
