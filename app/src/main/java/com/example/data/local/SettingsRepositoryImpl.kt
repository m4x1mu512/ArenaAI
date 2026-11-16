package com.example.data.local

import com.example.data.remote.CoarenaUrlBuilder
import com.example.data.security.SecureKeyStorage
import com.example.domain.model.AppSettings
import com.example.domain.model.ThemeMode
import com.example.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default implementation of [SettingsRepository] backed by [SecureKeyStorage] and [PreferencesManager].
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val secureKeyStorage: SecureKeyStorage,
    private val preferencesManager: PreferencesManager,
) : SettingsRepository {

    private val hasApiKeyFlow = MutableStateFlow(false)
    private val initializedFlow = MutableStateFlow(false)

    override val isInitialized: Flow<Boolean> = initializedFlow

    override val settingsFlow: Flow<AppSettings> = combine(
        preferencesManager.preferencesFlow,
        hasApiKeyFlow,
    ) { prefs, hasKey ->
        AppSettings(
            baseUrl = prefs.baseUrl,
            defaultModelId = prefs.defaultModelId,
            themeMode = prefs.themeMode,
            hasApiKey = hasKey,
        )
    }.onStart {
        refreshKeyPresence()
    }

    private suspend fun refreshKeyPresence() {
        val hasKey = !secureKeyStorage.getApiKey().isNullOrBlank()
        hasApiKeyFlow.value = hasKey
        initializedFlow.value = true
    }

    override suspend fun getApiKey(): String? {
        val key = secureKeyStorage.getApiKey()?.takeIf { it.isNotBlank() }
        hasApiKeyFlow.value = key != null
        initializedFlow.value = true
        return key
    }

    override suspend fun saveCredentials(apiKey: String, baseUrl: String) {
        val normalizedUrl = CoarenaUrlBuilder.normalizeBaseUrl(baseUrl)
        secureKeyStorage.saveApiKey(apiKey.trim())
        preferencesManager.setBaseUrl(normalizedUrl)
        hasApiKeyFlow.value = apiKey.trim().isNotEmpty()
        initializedFlow.value = true
    }

    override suspend fun saveBaseUrl(baseUrl: String) {
        val normalizedUrl = CoarenaUrlBuilder.normalizeBaseUrl(baseUrl)
        preferencesManager.setBaseUrl(normalizedUrl)
    }

    override suspend fun saveDefaultModel(modelId: String?) {
        preferencesManager.setDefaultModel(modelId)
    }

    override suspend fun saveThemeMode(themeMode: ThemeMode) {
        preferencesManager.setThemeMode(themeMode)
    }

    override suspend fun deleteApiKey() {
        secureKeyStorage.deleteApiKey()
        hasApiKeyFlow.value = false
    }

    override suspend fun clearAllLocalData() {
        secureKeyStorage.deleteApiKey()
        preferencesManager.clearAll()
        hasApiKeyFlow.value = false
    }
}
