package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.ApiWireFormat
import com.example.domain.model.AppSettings
import com.example.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.coarenaDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "coarena_preferences",
)

/**
 * Persists non-secret user settings (Base URL, default model, theme mode, streaming, wire format)
 * in Jetpack DataStore.
 */
class PreferencesManager(
    private val context: Context,
) {
    /** Emits non-secret preferences whenever they change. */
    val preferencesFlow: Flow<PreferencesSnapshot> = context.coarenaDataStore.data.map { prefs ->
        val rawUrl = prefs[KEY_BASE_URL]?.takeIf { it.isNotBlank() }
            ?: AppSettings.DEFAULT_BASE_URL
        val defaultModel = prefs[KEY_DEFAULT_MODEL]?.takeIf { it.isNotBlank() }
        val themeMode = prefs[KEY_THEME_MODE]?.let { raw ->
            runCatching { ThemeMode.valueOf(raw) }.getOrNull()
        } ?: ThemeMode.SYSTEM
        val useStreaming = prefs[KEY_USE_STREAMING] ?: true
        val wireFormat = prefs[KEY_WIRE_FORMAT]?.let { raw ->
            runCatching { ApiWireFormat.valueOf(raw) }.getOrNull()
        } ?: ApiWireFormat.CHAT_COMPLETIONS
        PreferencesSnapshot(
            baseUrl = rawUrl,
            defaultModelId = defaultModel,
            themeMode = themeMode,
            useStreaming = useStreaming,
            wireFormat = wireFormat,
        )
    }

    /** Saves [baseUrl] to DataStore. */
    suspend fun setBaseUrl(baseUrl: String) {
        context.coarenaDataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = baseUrl.trim()
        }
    }

    /** Saves [modelId] as the default model, or clears it when null. */
    suspend fun setDefaultModel(modelId: String?) {
        context.coarenaDataStore.edit { prefs ->
            if (modelId.isNullOrBlank()) {
                prefs.remove(KEY_DEFAULT_MODEL)
            } else {
                prefs[KEY_DEFAULT_MODEL] = modelId
            }
        }
    }

    /** Saves the selected [themeMode]. */
    suspend fun setThemeMode(themeMode: ThemeMode) {
        context.coarenaDataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = themeMode.name
        }
    }

    /** Saves whether SSE streaming should be used by default. */
    suspend fun setUseStreaming(enabled: Boolean) {
        context.coarenaDataStore.edit { prefs ->
            prefs[KEY_USE_STREAMING] = enabled
        }
    }

    /** Saves the selected [wireFormat]. */
    suspend fun setWireFormat(wireFormat: ApiWireFormat) {
        context.coarenaDataStore.edit { prefs ->
            prefs[KEY_WIRE_FORMAT] = wireFormat.name
        }
    }

    /** Removes all stored preferences. */
    suspend fun clearAll() {
        context.coarenaDataStore.edit { prefs ->
            prefs.clear()
        }
    }

    data class PreferencesSnapshot(
        val baseUrl: String,
        val defaultModelId: String?,
        val themeMode: ThemeMode,
        val useStreaming: Boolean,
        val wireFormat: ApiWireFormat,
    )

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_DEFAULT_MODEL = stringPreferencesKey("default_model_id")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_USE_STREAMING = booleanPreferencesKey("use_streaming")
        val KEY_WIRE_FORMAT = stringPreferencesKey("wire_format")
    }
}
