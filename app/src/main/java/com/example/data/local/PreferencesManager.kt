package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.coarenaDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "coarena_preferences",
)

/**
 * Persists non-secret user settings (Base URL, default model, theme mode) in Jetpack DataStore.
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
        PreferencesSnapshot(
            baseUrl = rawUrl,
            defaultModelId = defaultModel,
            themeMode = themeMode,
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
    )

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_DEFAULT_MODEL = stringPreferencesKey("default_model_id")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
