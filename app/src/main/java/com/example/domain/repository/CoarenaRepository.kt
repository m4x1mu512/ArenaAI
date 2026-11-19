package com.example.domain.repository

import com.example.domain.model.AiModel
import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatStreamEvent
import com.example.domain.model.ConnectionTestResult
import com.example.domain.model.CreditsInfo
import com.example.domain.model.GeneratedImage
import com.example.domain.model.LimitsInfo
import com.example.domain.model.RequestDiagnostic
import com.example.domain.model.RequestHistoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository for chat and model discovery across the legacy Coarena setup and configured providers.
 * Account credits, limits, and request history remain Coarena-specific.
 */
interface CoarenaRepository {
    /** Current selected provider connection; null means the legacy Coarena setup is active. */
    val activeProviderConnectionId: Flow<String?> get() = flowOf(null)
    /** Provider ID for the currently active connection, if any. */
    val activeProviderId: Flow<String?> get() = flowOf(null)

    /** Persists a model selection for the active provider (or the legacy default). */
    suspend fun saveSelectedModel(modelId: String) {}

    /** Returns the active provider's saved model, or the legacy default model. */
    suspend fun getSelectedModelId(): String? = null

    /** Emits non-secret diagnostic details of the most recent chat completion request. */
    val lastRequestDiagnostic: StateFlow<RequestDiagnostic?>

    /** Fetches models from every enabled saved provider connection. */
    suspend fun fetchModelsFromAllProviders(): Result<List<AiModel>> = fetchModels()

    /** Activates a saved provider connection before sending a request with its model. */
    suspend fun activateProviderConnection(connectionId: String?) {}

    /** Fetches available models using the currently saved credentials. */
    suspend fun fetchModels(): Result<List<AiModel>>

    /** Tests connectivity with explicit [apiKey] and [baseUrl] without persisting them first. */
    suspend fun testConnection(apiKey: String, baseUrl: String): ConnectionTestResult

    /**
     * Sends chat completion messages to [modelId], streaming SSE deltas when available and
     * automatically falling back to non-streaming JSON if SSE fails or returns JSON directly.
     */
    fun sendChatMessage(
        modelId: String,
        history: List<ChatMessage>,
        systemPrompt: String,
    ): Flow<ChatStreamEvent>

    /** Generates an image through the active provider's native image-generation API. */
    suspend fun generateImage(prompt: String): Result<GeneratedImage> =
        Result.failure(UnsupportedOperationException("Image generation is not available for this provider."))

    /** Fetches current account credit balance from `GET /credits`. */
    suspend fun fetchCredits(): Result<CreditsInfo>

    /** Fetches current account limits from `GET /limits`. */
    suspend fun fetchLimits(): Result<LimitsInfo>

    /** Fetches recent request logs from `GET /requests`. */
    suspend fun fetchRequests(): Result<List<RequestHistoryItem>>
}
