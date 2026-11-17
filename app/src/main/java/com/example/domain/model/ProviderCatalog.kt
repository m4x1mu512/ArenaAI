package com.example.domain.model

/**
 * Built-in connection templates. These presets describe API endpoints and wire protocols;
 * they do not claim that every model exposed by a provider supports every capability.
 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val protocol: ProviderProtocol,
    val description: String,
    val requiresCustomHeaders: Boolean = false,
)

/**
 * Catalog of providers that can be added from Settings.
 *
 * OpenAI-compatible providers share the existing chat-completions adapter. Anthropic uses
 * its native Messages protocol and therefore requires a dedicated adapter before it can
 * be selected for chat. A custom endpoint is always treated as OpenAI-compatible.
 */
object ProviderCatalog {
    val presets: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "coarena",
            displayName = "Coarena",
            baseUrl = "https://coarena.ai/api/v1",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            description = "Текущий провайдер ArenaAI",
        ),
        ProviderPreset(
            id = "openai",
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            description = "Официальный OpenAI API",
        ),
        ProviderPreset(
            id = "anthropic",
            displayName = "Anthropic",
            baseUrl = "https://api.anthropic.com/v1",
            protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
            description = "Claude API через нативный Messages API",
        ),
        ProviderPreset(
            id = "google-gemini",
            displayName = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            description = "Gemini через OpenAI-совместимый интерфейс",
        ),
        ProviderPreset(
            id = "deepseek",
            displayName = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            description = "DeepSeek API",
        ),
        ProviderPreset(
            id = "openrouter",
            displayName = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            description = "Доступ к моделям разных поставщиков через единый API",
        ),
        ProviderPreset(
            id = "groq",
            displayName = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            description = "Быстрый inference API",
        ),
        ProviderPreset(
            id = "mistral",
            displayName = "Mistral AI",
            baseUrl = "https://api.mistral.ai/v1",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            description = "Mistral API",
        ),
        ProviderPreset(
            id = "together",
            displayName = "Together AI",
            baseUrl = "https://api.together.xyz/v1",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            description = "Модели с OpenAI-совместимым API",
        ),
        ProviderPreset(
            id = "xai",
            displayName = "xAI",
            baseUrl = "https://api.x.ai/v1",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            description = "Grok API",
        ),
    )

    val customPreset = ProviderPreset(
        id = "custom-openai-compatible",
        displayName = "Свой OpenAI-совместимый сервер",
        baseUrl = "",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        description = "Укажите собственные Base URL и API Key",
    )

    fun find(id: String): ProviderPreset? = presets.firstOrNull { it.id == id }
}
