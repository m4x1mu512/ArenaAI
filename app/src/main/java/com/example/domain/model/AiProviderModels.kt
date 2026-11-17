package com.example.domain.model

/**
 * Wire protocol used by an AI provider adapter.
 */
enum class ProviderProtocol {
    OPENAI_COMPATIBLE,
    ANTHROPIC_MESSAGES,
    CUSTOM,
}

/**
 * Capabilities exposed by a provider/model pair.
 *
 * Values are descriptive and must be populated from verified provider/model metadata,
 * not inferred from the provider name.
 */
data class ProviderCapabilities(
    val supportsStreaming: Boolean = false,
    val supportsImageInput: Boolean = false,
    val supportsImageGeneration: Boolean = false,
    val maxImagesPerRequest: Int = 0,
)

/**
 * Stable, non-secret identity and capabilities of an AI provider.
 */
data class AiProviderDescriptor(
    val id: String,
    val displayName: String,
    val protocol: ProviderProtocol,
    val capabilities: ProviderCapabilities = ProviderCapabilities(),
)

/**
 * Non-secret connection settings. Credentials are deliberately stored separately.
 *
 * [credentialId] points to a secret held by the app's secure storage; it is never
 * the API key itself.
 */
data class ProviderConnection(
    val id: String,
    val providerId: String,
    val baseUrl: String,
    val credentialId: String,
    val wireFormat: ApiWireFormat = ApiWireFormat.CHAT_COMPLETIONS,
    val isEnabled: Boolean = true,
    val displayName: String = providerId,
    val defaultModelId: String? = null,
    val gatewayId: String? = null,
    val manualModelIds: String? = null,
)

/**
 * Short-lived credentials supplied to a provider adapter at request time.
 * Avoid logging or persisting instances of this class.
 */
class ProviderCredentials(
    val apiKey: String,
) {
    override fun toString(): String = "ProviderCredentials(apiKey=[REDACTED])"
}

/**
 * Provider-neutral request passed from the chat domain to an adapter.
 */
data class ProviderChatRequest(
    val modelId: String,
    val history: List<ChatMessage>,
    val systemPrompt: String,
    val useStreaming: Boolean = true,
)

/**
 * Provider-neutral image-generation request.
 */
data class ImageGenerationRequest(
    val modelId: String,
    val prompt: String,
    val negativePrompt: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * Generated image payload returned by an image-generation adapter.
 *
 * Adapters should return either a temporary [remoteUrl] or [base64Data].
 * The app must copy the image into private local storage before saving it to history.
 */
data class GeneratedImage(
    val mimeType: String,
    val remoteUrl: String? = null,
    val base64Data: String? = null,
    val revisedPrompt: String? = null,
)
