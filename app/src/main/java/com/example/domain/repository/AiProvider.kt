package com.example.domain.repository

import com.example.domain.model.AiModel
import com.example.domain.model.AiProviderDescriptor
import com.example.domain.model.GeneratedImage
import com.example.domain.model.ImageGenerationRequest
import com.example.domain.model.ProviderChatRequest
import com.example.domain.model.ProviderConnection
import com.example.domain.model.ProviderCredentials
import com.example.domain.model.ChatStreamEvent
import kotlinx.coroutines.flow.Flow

/**
 * Adapter contract for a chat-capable AI provider.
 *
 * Implementations translate provider-neutral requests into the provider's wire format.
 * Credentials are supplied for the request and must never be persisted or logged here.
 */
interface ChatProvider {
    val descriptor: AiProviderDescriptor

    suspend fun fetchModels(
        connection: ProviderConnection,
        credentials: ProviderCredentials,
    ): Result<List<AiModel>>

    fun sendChatMessage(
        connection: ProviderConnection,
        credentials: ProviderCredentials,
        request: ProviderChatRequest,
    ): Flow<ChatStreamEvent>
}

/**
 * Optional image-generation capability. Kept separate because a provider may support
 * image understanding without offering image generation.
 */
interface ImageGenerationProvider {
    val descriptor: AiProviderDescriptor

    suspend fun generateImage(
        connection: ProviderConnection,
        credentials: ProviderCredentials,
        request: ImageGenerationRequest,
    ): Result<List<GeneratedImage>>
}
