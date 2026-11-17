package com.example.data.remote

import com.example.domain.model.AiProviderDescriptor
import com.example.domain.model.ApiWireFormat
import com.example.domain.model.ProviderCapabilities
import com.example.domain.model.ProviderProtocol
import com.example.domain.repository.ChatProvider
import com.example.domain.model.AiModel
import com.example.domain.model.ChatStreamEvent
import com.example.domain.model.ProviderChatRequest
import com.example.domain.model.ProviderConnection
import com.example.domain.model.ProviderCredentials
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provider-layer adapter for the existing Coarena network client.
 *
 * This adapter intentionally delegates to the established network implementation so the
 * current chat flow, SSE fallback, diagnostics, and error handling remain unchanged.
 */
@Singleton
class CoarenaChatProviderAdapter @Inject constructor(
    private val networkClient: CoarenaNetworkClient,
) : ChatProvider {

    override val descriptor = AiProviderDescriptor(
        id = PROVIDER_ID,
        displayName = "Coarena",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        capabilities = ProviderCapabilities(
            supportsStreaming = true,
            supportsImageInput = true,
            supportsImageGeneration = false,
            maxImagesPerRequest = MAX_IMAGES_PER_REQUEST,
        ),
    )

    override suspend fun fetchModels(
        connection: ProviderConnection,
        credentials: ProviderCredentials,
    ): Result<List<AiModel>> {
        require(connection.providerId == PROVIDER_ID) {
            "Provider connection does not belong to Coarena"
        }
        return networkClient.fetchModels(connection.baseUrl, credentials.apiKey)
    }

    override fun sendChatMessage(
        connection: ProviderConnection,
        credentials: ProviderCredentials,
        request: ProviderChatRequest,
    ): Flow<ChatStreamEvent> {
        require(connection.providerId == PROVIDER_ID) {
            "Provider connection does not belong to Coarena"
        }
        return networkClient.streamChatCompletion(
            baseUrl = connection.baseUrl,
            apiKey = credentials.apiKey,
            modelId = request.modelId,
            history = request.history,
            systemPrompt = request.systemPrompt,
            useStreaming = request.useStreaming,
            wireFormat = connection.wireFormat.takeIf { it == ApiWireFormat.RESPONSES }
                ?: ApiWireFormat.CHAT_COMPLETIONS,
        )
    }

    companion object {
        const val PROVIDER_ID = "coarena"
        const val MAX_IMAGES_PER_REQUEST = 8
    }
}
