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
import com.example.domain.model.ProviderConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

    /** Emits saved provider connections (metadata only; never API keys). */
    val providerConnectionsFlow: Flow<List<ProviderConnection>> = context.coarenaDataStore.data.map { prefs ->
        decodeConnections(prefs[KEY_PROVIDER_CONNECTIONS].orEmpty())
    }

    /** Emits the active provider connection ID. */
    val activeProviderConnectionIdFlow: Flow<String?> = context.coarenaDataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_PROVIDER_CONNECTION]
    }

    suspend fun setProviderConnections(connections: List<ProviderConnection>) {
        context.coarenaDataStore.edit { prefs ->
            prefs[KEY_PROVIDER_CONNECTIONS] = encodeConnections(connections)
        }
    }

    suspend fun setActiveProviderConnectionId(id: String?) {
        context.coarenaDataStore.edit { prefs ->
            if (id.isNullOrBlank()) prefs.remove(KEY_ACTIVE_PROVIDER_CONNECTION)
            else prefs[KEY_ACTIVE_PROVIDER_CONNECTION] = id
        }
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

    private fun encodeConnections(connections: List<ProviderConnection>): String =
        buildJsonArray {
            connections.forEach { connection ->
                add(buildJsonObject {
                    put("id", connection.id)
                    put("providerId", connection.providerId)
                    put("baseUrl", connection.baseUrl)
                    put("credentialId", connection.credentialId)
                    put("wireFormat", connection.wireFormat.name)
                    put("isEnabled", connection.isEnabled)
                    put("displayName", connection.displayName)
                    connection.defaultModelId?.let { put("defaultModelId", it) }
                })
            }
        }.toString()

    private fun decodeConnections(raw: String): List<ProviderConnection> = runCatching {
        Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            val item = element.jsonObject
            val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val providerId = item["providerId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val baseUrl = item["baseUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val credentialId = item["credentialId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val wireFormat = item["wireFormat"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { ApiWireFormat.valueOf(it) }.getOrNull() }
                ?: ApiWireFormat.CHAT_COMPLETIONS
            ProviderConnection(
                id = id,
                providerId = providerId,
                baseUrl = baseUrl,
                credentialId = credentialId,
                wireFormat = wireFormat,
                isEnabled = item["isEnabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true,
                displayName = item["displayName"]?.jsonPrimitive?.contentOrNull ?: providerId,
                defaultModelId = item["defaultModelId"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_DEFAULT_MODEL = stringPreferencesKey("default_model_id")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_USE_STREAMING = booleanPreferencesKey("use_streaming")
        val KEY_WIRE_FORMAT = stringPreferencesKey("wire_format")
        val KEY_PROVIDER_CONNECTIONS = stringPreferencesKey("provider_connections")
        val KEY_ACTIVE_PROVIDER_CONNECTION = stringPreferencesKey("active_provider_connection")
    }
}
