package com.example.domain.model

/**
 * Application color theme preference.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Persisted user preferences (excluding the encrypted API key).
 */
data class AppSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val defaultModelId: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val hasApiKey: Boolean = false,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://coarena.ai/api/v1"
    }
}
