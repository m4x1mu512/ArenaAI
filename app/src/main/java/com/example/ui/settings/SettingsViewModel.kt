package com.example.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.remote.CoarenaUrlBuilder
import com.example.domain.model.AiModel
import com.example.domain.model.ApiWireFormat
import com.example.domain.model.AppError
import com.example.domain.model.AppSettings
import com.example.domain.model.CreditsInfo
import com.example.domain.model.LimitsInfo
import com.example.domain.model.RequestDiagnostic
import com.example.domain.model.RequestHistoryItem
import com.example.domain.model.ThemeMode
import com.example.domain.repository.ChatHistoryRepository
import com.example.domain.repository.CoarenaRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.repository.ProviderConnectionsRepository
import com.example.ui.common.toAppError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Settings, Account Usage, and Key Management screen.
 */
data class SettingsUiState(
    val apiKeyInput: String = "",
    val isKeyVisible: Boolean = false,
    val hasSavedApiKey: Boolean = false,
    val baseUrlInput: String = AppSettings.DEFAULT_BASE_URL,
    val defaultModelId: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useStreaming: Boolean = true,
    val wireFormat: ApiWireFormat = ApiWireFormat.CHAT_COMPLETIONS,
    val lastDiagnostic: RequestDiagnostic? = null,
    val models: List<AiModel> = emptyList(),
    val isLoadingModels: Boolean = false,
    val isTestingConnection: Boolean = false,
    val testSuccessModelCount: Int? = null,
    val hasTestSuccess: Boolean = false,
    val connectionError: AppError? = null,
    val validationErrorResId: Int? = null,
    val isSaving: Boolean = false,
    val credits: CreditsInfo? = null,
    val isLoadingCredits: Boolean = false,
    val creditsError: AppError? = null,
    val limits: LimitsInfo? = null,
    val isLoadingLimits: Boolean = false,
    val limitsError: AppError? = null,
    val requests: List<RequestHistoryItem> = emptyList(),
    val isLoadingRequests: Boolean = false,
    val requestsError: AppError? = null,
    val showSavedConfirmation: Boolean = false,
)

/**
 * ViewModel managing credential updates, default model, theme, credits, limits, request history,
 * and local data deletion.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val coarenaRepository: CoarenaRepository,
    private val chatHistoryRepository: ChatHistoryRepository,
    private val providerConnectionsRepository: ProviderConnectionsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadInitialState()
        observeDiagnostic()
    }

    private fun observeDiagnostic() {
        viewModelScope.launch {
            coarenaRepository.lastRequestDiagnostic.collect { diagnostic ->
                _uiState.update { it.copy(lastDiagnostic = diagnostic) }
            }
        }
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            val hasKey = !settingsRepository.getApiKey().isNullOrBlank()
            _uiState.update {
                it.copy(
                    hasSavedApiKey = hasKey,
                    baseUrlInput = settings.baseUrl,
                    defaultModelId = coarenaRepository.getSelectedModelId(),
                    themeMode = settings.themeMode,
                    useStreaming = settings.useStreaming,
                    wireFormat = settings.wireFormat,
                    lastDiagnostic = coarenaRepository.lastRequestDiagnostic.value,
                )
            }
            if (hasKey) {
                refreshAccountData()
            }
        }
    }

    /** Updates the replacement API key field. */
    fun onApiKeyChange(value: String) {
        _uiState.update {
            it.copy(
                apiKeyInput = value,
                validationErrorResId = null,
                connectionError = null,
                hasTestSuccess = false,
            )
        }
    }

    /** Toggles masked vs visible display of the API key field. */
    fun toggleKeyVisibility() {
        _uiState.update { it.copy(isKeyVisible = !it.isKeyVisible) }
    }

    /** Updates the Base URL field. */
    fun onBaseUrlChange(value: String) {
        _uiState.update {
            it.copy(
                baseUrlInput = value,
                validationErrorResId = null,
                connectionError = null,
                hasTestSuccess = false,
            )
        }
    }

    /** Restores default Coarena Base URL in the input field. */
    fun resetBaseUrl() {
        _uiState.update {
            it.copy(
                baseUrlInput = AppSettings.DEFAULT_BASE_URL,
                validationErrorResId = null,
                connectionError = null,
            )
        }
    }

    /** Updates and persists the default model selection. */
    fun selectDefaultModel(modelId: String) {
        _uiState.update { it.copy(defaultModelId = modelId) }
        viewModelScope.launch {
            coarenaRepository.saveSelectedModel(modelId)
        }
    }

    /** Updates and persists the UI color theme mode. */
    fun selectThemeMode(themeMode: ThemeMode) {
        _uiState.update { it.copy(themeMode = themeMode) }
        viewModelScope.launch {
            settingsRepository.saveThemeMode(themeMode)
        }
    }

    /** Updates whether SSE streaming (`stream: true`) is enabled for chat requests. */
    fun setUseStreaming(enabled: Boolean) {
        _uiState.update { it.copy(useStreaming = enabled) }
        viewModelScope.launch {
            settingsRepository.saveUseStreaming(enabled)
        }
    }

    /** Updates the OpenAI wire endpoint mode (`/chat/completions` vs `/responses`). */
    fun setWireFormat(wireFormat: ApiWireFormat) {
        _uiState.update { it.copy(wireFormat = wireFormat) }
        viewModelScope.launch {
            settingsRepository.saveWireFormat(wireFormat)
        }
    }

    /** Consumes the one-shot saved confirmation flag. */
    fun consumeSavedConfirmation() {
        _uiState.update { it.copy(showSavedConfirmation = false) }
    }

    /** Tests connectivity using the typed key (if any) or the stored key, and typed Base URL. */
    fun testConnection() {
        val state = _uiState.value
        if (!CoarenaUrlBuilder.isValidBaseUrl(state.baseUrlInput)) {
            _uiState.update {
                it.copy(validationErrorResId = R.string.setup_validation_invalid_url)
            }
            return
        }

        viewModelScope.launch {
            val effectiveKey = state.apiKeyInput.trim().ifEmpty {
                settingsRepository.getApiKey().orEmpty()
            }
            if (effectiveKey.isEmpty()) {
                _uiState.update {
                    it.copy(validationErrorResId = R.string.setup_validation_empty_key)
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isTestingConnection = true,
                    validationErrorResId = null,
                    connectionError = null,
                    hasTestSuccess = false,
                    testSuccessModelCount = null,
                )
            }
            val result = coarenaRepository.testConnection(
                apiKey = effectiveKey,
                baseUrl = state.baseUrlInput.trim(),
            )
            _uiState.update {
                if (result.isSuccess) {
                    it.copy(
                        isTestingConnection = false,
                        hasTestSuccess = true,
                        testSuccessModelCount = result.modelCount,
                        connectionError = null,
                    )
                } else {
                    it.copy(
                        isTestingConnection = false,
                        hasTestSuccess = false,
                        testSuccessModelCount = null,
                        connectionError = result.error,
                    )
                }
            }
        }
    }

    /** Saves modified Base URL and optional new API key locally, then refreshes account endpoints. */
    fun saveChanges() {
        val state = _uiState.value
        if (!CoarenaUrlBuilder.isValidBaseUrl(state.baseUrlInput)) {
            _uiState.update {
                it.copy(validationErrorResId = R.string.setup_validation_invalid_url)
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, validationErrorResId = null) }
            val newKey = state.apiKeyInput.trim()
            if (newKey.isNotEmpty()) {
                settingsRepository.saveCredentials(newKey, state.baseUrlInput.trim())
            } else {
                settingsRepository.saveBaseUrl(state.baseUrlInput.trim())
            }
            val hasKey = !settingsRepository.getApiKey().isNullOrBlank()
            _uiState.update {
                it.copy(
                    isSaving = false,
                    apiKeyInput = "",
                    hasSavedApiKey = hasKey,
                    showSavedConfirmation = true,
                )
            }
            if (hasKey) {
                refreshAccountData()
            }
        }
    }

    /** Fetches models, credits, limits, and recent request history concurrently. */
    fun refreshAccountData() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingModels = true,
                    isLoadingCredits = true,
                    isLoadingLimits = true,
                    isLoadingRequests = true,
                    creditsError = null,
                    limitsError = null,
                    requestsError = null,
                )
            }

            coroutineScope {
                val modelsDeferred = async { coarenaRepository.fetchModels() }
                val creditsDeferred = async { coarenaRepository.fetchCredits() }
                val limitsDeferred = async { coarenaRepository.fetchLimits() }
                val requestsDeferred = async { coarenaRepository.fetchRequests() }

                val modelsRes = modelsDeferred.await()
                val creditsRes = creditsDeferred.await()
                val limitsRes = limitsDeferred.await()
                val requestsRes = requestsDeferred.await()

                _uiState.update { current ->
                    current.copy(
                        models = modelsRes.getOrElse { current.models },
                        isLoadingModels = false,
                        credits = creditsRes.getOrNull(),
                        isLoadingCredits = false,
                        creditsError = creditsRes.exceptionOrNull()?.toAppError(),
                        limits = limitsRes.getOrNull(),
                        isLoadingLimits = false,
                        limitsError = limitsRes.exceptionOrNull()?.toAppError(),
                        requests = requestsRes.getOrElse { emptyList() },
                        isLoadingRequests = false,
                        requestsError = requestsRes.exceptionOrNull()?.toAppError(),
                    )
                }
            }
        }
    }

    /** Deletes only the encrypted API key and invokes [onKeyDeleted]. */
    fun deleteSavedKey(onKeyDeleted: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.deleteApiKey()
            _uiState.update {
                it.copy(
                    apiKeyInput = "",
                    hasSavedApiKey = false,
                    credits = null,
                    limits = null,
                    requests = emptyList(),
                )
            }
            onKeyDeleted()
        }
    }

    /** Clears all local credentials, preferences, and saved chat history and invokes [onDataCleared]. */
    fun clearAllLocalData(onDataCleared: () -> Unit) {
        viewModelScope.launch {
            providerConnectionsRepository.clearAllConnections()
            settingsRepository.clearAllLocalData()
            chatHistoryRepository.clearAllHistory()
            _uiState.update { SettingsUiState() }
            onDataCleared()
        }
    }
}
