package com.example.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.remote.CoarenaUrlBuilder
import com.example.domain.model.AppError
import com.example.domain.model.AppSettings
import com.example.domain.model.ConnectionTestResult
import com.example.domain.repository.CoarenaRepository
import com.example.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the initial onboarding / credential setup screen.
 */
data class SetupUiState(
    val apiKeyInput: String = "",
    val baseUrlInput: String = AppSettings.DEFAULT_BASE_URL,
    val isKeyVisible: Boolean = false,
    val isTesting: Boolean = false,
    val isSaving: Boolean = false,
    val connectionSuccessModelCount: Int? = null,
    val hasSuccessfulTest: Boolean = false,
    val validationErrorResId: Int? = null,
    val requestError: AppError? = null,
)

/**
 * ViewModel managing first-launch API key entry, Base URL validation, and connection testing.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val coarenaRepository: CoarenaRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val currentSettings = settingsRepository.settingsFlow.first()
            _uiState.update { state ->
                state.copy(baseUrlInput = currentSettings.baseUrl)
            }
        }
    }

    /** Updates the API key text input without logging or persisting plaintext. */
    fun onApiKeyChange(value: String) {
        _uiState.update {
            it.copy(
                apiKeyInput = value,
                validationErrorResId = null,
                requestError = null,
                hasSuccessfulTest = false,
                connectionSuccessModelCount = null,
            )
        }
    }

    /** Updates the Base URL text input. */
    fun onBaseUrlChange(value: String) {
        _uiState.update {
            it.copy(
                baseUrlInput = value,
                validationErrorResId = null,
                requestError = null,
                hasSuccessfulTest = false,
                connectionSuccessModelCount = null,
            )
        }
    }

    /** Toggles masked vs visible display of the API key field. */
    fun toggleKeyVisibility() {
        _uiState.update { it.copy(isKeyVisible = !it.isKeyVisible) }
    }

    /** Restores the default Coarena Base URL. */
    fun resetBaseUrl() {
        _uiState.update {
            it.copy(
                baseUrlInput = AppSettings.DEFAULT_BASE_URL,
                validationErrorResId = null,
                requestError = null,
            )
        }
    }

    /** Validates inputs and executes a test `GET /models` request against Coarena. */
    fun testConnection() {
        val snapshot = _uiState.value
        val validationError = validateInputs(snapshot.apiKeyInput, snapshot.baseUrlInput)
        if (validationError != null) {
            _uiState.update { it.copy(validationErrorResId = validationError, requestError = null) }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isTesting = true,
                    validationErrorResId = null,
                    requestError = null,
                    hasSuccessfulTest = false,
                    connectionSuccessModelCount = null,
                )
            }
            val result: ConnectionTestResult = coarenaRepository.testConnection(
                apiKey = snapshot.apiKeyInput.trim(),
                baseUrl = snapshot.baseUrlInput.trim(),
            )
            _uiState.update {
                if (result.isSuccess) {
                    it.copy(
                        isTesting = false,
                        hasSuccessfulTest = true,
                        connectionSuccessModelCount = result.modelCount,
                        requestError = null,
                    )
                } else {
                    it.copy(
                        isTesting = false,
                        hasSuccessfulTest = false,
                        connectionSuccessModelCount = null,
                        requestError = result.error,
                    )
                }
            }
        }
    }

    /** Validates inputs, encrypts and saves credentials locally, then invokes [onSuccess]. */
    fun saveAndContinue(onSuccess: () -> Unit) {
        val snapshot = _uiState.value
        val validationError = validateInputs(snapshot.apiKeyInput, snapshot.baseUrlInput)
        if (validationError != null) {
            _uiState.update { it.copy(validationErrorResId = validationError) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, validationErrorResId = null) }
            try {
                settingsRepository.saveCredentials(
                    apiKey = snapshot.apiKeyInput.trim(),
                    baseUrl = snapshot.baseUrlInput.trim(),
                )
                _uiState.update { it.copy(isSaving = false, apiKeyInput = "") }
                onSuccess()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        requestError = AppError.Unknown(e.message ?: "Failed to save credentials"),
                    )
                }
            }
        }
    }

    private fun validateInputs(apiKey: String, baseUrl: String): Int? {
        if (apiKey.trim().isEmpty()) {
            return R.string.setup_validation_empty_key
        }
        if (!CoarenaUrlBuilder.isValidBaseUrl(baseUrl)) {
            return R.string.setup_validation_invalid_url
        }
        return null
    }
}
