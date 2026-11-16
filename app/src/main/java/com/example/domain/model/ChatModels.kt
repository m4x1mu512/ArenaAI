package com.example.domain.model

/**
 * Role of a message participant in an OpenAI-compatible chat conversation.
 */
enum class MessageRole(val apiValue: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
}

/**
 * Single message item displayed in the active chat thread.
 *
 * @property id Stable unique identifier for Compose list keys.
 * @property role Participant role ([MessageRole.USER] or [MessageRole.ASSISTANT]).
 * @property content Text content of the message.
 * @property timestampMillis Epoch timestamp in milliseconds.
 * @property modelId Optional model identifier that produced the assistant response.
 * @property isStreaming True while SSE chunks are still arriving.
 * @property isError True if this message failed to complete.
 * @property totalTokens Optional total token usage reported by the API.
 */
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val modelId: String? = null,
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val totalTokens: Int? = null,
)

/**
 * AI model descriptor returned by `GET /api/v1/models`.
 *
 * @property id Model identifier sent in `chat/completions` requests.
 * @property displayName Human-friendly display name (falls back to [id]).
 * @property ownedBy Optional provider or organization name.
 * @property contextLength Optional maximum context window size.
 */
data class AiModel(
    val id: String,
    val displayName: String = id,
    val ownedBy: String? = null,
    val contextLength: Int? = null,
)

/**
 * Events emitted while streaming or completing a chat request.
 */
sealed interface ChatStreamEvent {
    /** Incremental text token/chunk appended to the assistant message. */
    data class Delta(
        val textChunk: String,
        val responseModelId: String? = null,
    ) : ChatStreamEvent

    /** Final completion event containing optional token usage and server-reported model ID. */
    data class Completed(
        val fullText: String? = null,
        val totalTokens: Int? = null,
        val responseModelId: String? = null,
    ) : ChatStreamEvent
}

/**
 * Non-secret diagnostic snapshot of the latest chat completion API request.
 */
data class RequestDiagnostic(
    val endpointUrl: String,
    val sentModelId: String,
    val stream: Boolean,
    val wireFormat: ApiWireFormat,
    val httpStatusCode: Int? = null,
    val responseModelId: String? = null,
    val headerModelId: String? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
)
