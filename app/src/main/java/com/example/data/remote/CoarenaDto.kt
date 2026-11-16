package com.example.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request message payload for OpenAI-compatible `/chat/completions`.
 */
@Serializable
data class ChatMessageDto(
    val role: String,
    val content: String,
)

/**
 * Request body sent to `POST {baseUrl}/chat/completions`.
 */
@Serializable
data class ChatCompletionRequestDto(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean = true,
)

/**
 * Request body sent to `POST {baseUrl}/responses` (OpenAI Responses wire format).
 */
@Serializable
data class ResponsesRequestDto(
    val model: String,
    val input: List<ChatMessageDto>,
    val instructions: String? = null,
    val stream: Boolean = true,
)

/**
 * Token usage summary optionally returned by chat completion or request logs.
 */
@Serializable
data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)
