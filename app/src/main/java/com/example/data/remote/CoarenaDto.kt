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


/** OpenAI-compatible multimodal message content for image-aware chat completions. */
@Serializable
data class MultimodalChatMessageDto(
    val role: String,
    val content: List<MultimodalContentPartDto>,
)

@Serializable
data class MultimodalContentPartDto(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: ImageUrlDto? = null,
)

@Serializable
data class ImageUrlDto(
    val url: String,
)

/** Responses API input content uses input_text/input_image part names. */
@Serializable
data class ResponsesInputMessageDto(
    val role: String,
    val content: List<ResponsesInputPartDto>,
)

@Serializable
data class ResponsesInputPartDto(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
)
