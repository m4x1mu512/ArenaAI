package com.example.domain.model

/**
 * Summary of a persisted local conversation thread.
 *
 * @property id Unique conversation identifier.
 * @property title Short title derived from the first user prompt.
 * @property modelId Model used in the conversation.
 * @property createdAtMillis Creation epoch timestamp in milliseconds.
 * @property updatedAtMillis Last activity epoch timestamp in milliseconds.
 * @property messageCount Total persisted messages in this conversation.
 * @property lastMessagePreview Short preview snippet of the latest message.
 */
data class ChatSession(
    val id: String,
    val title: String,
    val modelId: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val messageCount: Int = 0,
    val lastMessagePreview: String = "",
)
