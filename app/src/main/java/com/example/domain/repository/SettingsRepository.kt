package com.example.domain.repository

import com.example.domain.model.ApiWireFormat
import com.example.domain.model.AppSettings
import com.example.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Manages secure local storage of the API key and DataStore-backed app preferences.
 */
interface SettingsRepository {
    /** Emits current non-secret preferences plus [AppSettings.hasApiKey] status. */
    val settingsFlow: Flow<AppSettings>

    /** Emits true once initial settings and secure key availability have been loaded. */
    val isInitialized: Flow<Boolean>

    /** Reads the decrypted API key from local secure storage, or null if absent. */
    suspend fun getApiKey(): String?

    /** Encrypts and saves [apiKey] locally, and updates [baseUrl]. */
    suspend fun saveCredentials(apiKey: String, baseUrl: String)

    /** Updates only the Base URL in DataStore. */
    suspend fun saveBaseUrl(baseUrl: String)

    /** Updates the default model ID in DataStore. */
    suspend fun saveDefaultModel(modelId: String?)

    /** Updates the UI theme preference in DataStore. */
    suspend fun saveThemeMode(themeMode: ThemeMode)

    /** Enables or disables SSE streaming for chat completions. */
    suspend fun saveUseStreaming(enabled: Boolean)

    /** Selects OpenAI wire endpoint format (`/chat/completions` vs `/responses`). */
    suspend fun saveWireFormat(wireFormat: ApiWireFormat)

    /** Deletes only the saved API key from secure storage. */
    suspend fun deleteApiKey()

    /** Deletes all local data: encrypted API key and all DataStore preferences. */
    suspend fun clearAllLocalData()
}
