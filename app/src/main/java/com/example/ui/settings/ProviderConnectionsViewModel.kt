package com.example.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.remote.CoarenaNetworkClient
import com.example.data.remote.AnthropicNetworkClient
import com.example.domain.model.ProviderCatalog
import com.example.domain.model.ProviderConnection
import com.example.domain.repository.ProviderConnectionsRepository
import com.example.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ProviderConnectionsUiState(
    val connections: List<ProviderConnection> = emptyList(),
    val activeConnectionId: String? = null,
    val selectedPresetId: String = ProviderCatalog.presets.first().id,
    val displayName: String = ProviderCatalog.presets.first().displayName,
    val baseUrl: String = ProviderCatalog.presets.first().baseUrl,
    val accountId: String = "",
    val gatewayId: String = "default",
    val defaultModelId: String = "",
    val apiKey: String = "",
    val isTesting: Boolean = false,
    val testModelCount: Int? = null,
    val error: String? = null,
    val isSaving: Boolean = false,
    val editingConnectionId: String? = null,
    val hasLegacyCoarenaKey: Boolean = false,
)

@HiltViewModel
class ProviderConnectionsViewModel @Inject constructor(
    private val repository: ProviderConnectionsRepository,
    private val networkClient: CoarenaNetworkClient,
    private val anthropicNetworkClient: AnthropicNetworkClient,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProviderConnectionsUiState())
    val state: StateFlow<ProviderConnectionsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update { it.copy(hasLegacyCoarenaKey = !settingsRepository.getApiKey().isNullOrBlank()) }
        }
        viewModelScope.launch {
            repository.connections.collect { items -> _state.update { it.copy(connections = items) } }
        }
        viewModelScope.launch {
            repository.activeConnectionId.collect { id -> _state.update { it.copy(activeConnectionId = id) } }
        }
    }

    fun selectPreset(id: String) {
        val preset = ProviderCatalog.find(id) ?: if (id == ProviderCatalog.customPreset.id) ProviderCatalog.customPreset else return
        _state.update {
            it.copy(
                selectedPresetId = preset.id,
                displayName = preset.displayName,
                baseUrl = preset.baseUrl,
                accountId = "",
                gatewayId = "default",
                defaultModelId = "",
                error = null,
                testModelCount = null,
            )
        }
    }

    fun editConnection(id: String) {
        val connection = _state.value.connections.firstOrNull { it.id == id } ?: return
        val presetId = if (connection.providerId == ProviderCatalog.customPreset.id ||
            ProviderCatalog.find(connection.providerId) == null
        ) ProviderCatalog.customPreset.id else connection.providerId
        _state.update {
            it.copy(
                selectedPresetId = presetId,
                displayName = connection.displayName,
                baseUrl = connection.baseUrl,
                accountId = Regex("/accounts/([^/]+)/ai/v1").find(connection.baseUrl)?.groupValues?.getOrNull(1).orEmpty(),
                gatewayId = connection.gatewayId ?: "default",
                defaultModelId = connection.defaultModelId.orEmpty(),
                apiKey = "",
                editingConnectionId = id,
                error = null,
                testModelCount = null,
            )
        }
    }

    fun cancelEdit() {
        _state.update { it.copy(editingConnectionId = null, apiKey = "", error = null) }
    }

    fun setDisplayName(value: String) = _state.update { it.copy(displayName = value, error = null) }
    fun setBaseUrl(value: String) = _state.update { it.copy(baseUrl = value, error = null, testModelCount = null) }
    fun setAccountId(value: String) = _state.update { it.copy(accountId = value.trim(), error = null, testModelCount = null) }
    fun setGatewayId(value: String) = _state.update { it.copy(gatewayId = value.trim(), error = null) }
    fun setDefaultModelId(value: String) = _state.update { it.copy(defaultModelId = value.trim(), error = null) }
    fun setApiKey(value: String) = _state.update { it.copy(apiKey = value, error = null, testModelCount = null) }

    fun testConnection() {
        val snapshot = _state.value
        if (snapshot.baseUrl.isBlank()) {
            _state.update { it.copy(error = "Укажите Base URL.") }
            return
        }
        viewModelScope.launch {
            val existing = snapshot.editingConnectionId?.let { id ->
                snapshot.connections.firstOrNull { it.id == id }
            }
            val effectiveKey = snapshot.apiKey.trim().ifBlank {
                existing?.let { repository.getApiKey(it) }.orEmpty()
            }
            if (effectiveKey.isBlank()) {
                _state.update { it.copy(error = "Укажите API Key.") }
                return@launch
            }
            _state.update { it.copy(isTesting = true, error = null, testModelCount = null) }
            val result = if (snapshot.selectedPresetId == "anthropic") {
                anthropicNetworkClient.fetchModels(snapshot.baseUrl.trim(), effectiveKey)
            } else {
                networkClient.fetchModels(snapshot.baseUrl.trim(), effectiveKey)
            }
            _state.update {
                if (result.isSuccess) it.copy(isTesting = false, testModelCount = result.getOrThrow().size)
                else it.copy(isTesting = false, error = result.exceptionOrNull()?.message ?: "Не удалось подключиться.")
            }
        }
    }

    fun saveConnection(onSaved: () -> Unit = {}) {
        val snapshot = _state.value
        if (snapshot.displayName.isBlank() || snapshot.baseUrl.isBlank() ||
            (snapshot.apiKey.isBlank() && snapshot.editingConnectionId == null)
        ) {
            _state.update { it.copy(error = "Заполните название, Base URL и API Key.") }
            return
        }
        val preset = ProviderCatalog.find(snapshot.selectedPresetId)
            ?: if (snapshot.selectedPresetId == ProviderCatalog.customPreset.id) ProviderCatalog.customPreset else null
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            runCatching {
                val editing = snapshot.editingConnectionId?.let { id ->
                    snapshot.connections.firstOrNull { it.id == id }
                }
                if (editing != null) {
                    repository.updateConnection(
                        connection = editing.copy(
                            baseUrl = snapshot.baseUrl.trim(),
                            displayName = snapshot.displayName.trim(),
                        ),
                        replacementApiKey = snapshot.apiKey.takeIf { it.isNotBlank() },
                    )
                } else {
                    val id = UUID.randomUUID().toString().replace("-", "")
                    val providerId = preset?.id ?: ProviderCatalog.customPreset.id
                    repository.addConnection(
                        connection = ProviderConnection(
                            id = id,
                            providerId = providerId,
                            baseUrl = snapshot.baseUrl.trim(),
                            credentialId = "provider_$id",
                            displayName = snapshot.displayName.trim(),
                        ),
                        apiKey = snapshot.apiKey.trim(),
                        makeActive = true,
                    )
                }
            }.onSuccess {
                _state.update {
                    it.copy(
                        isSaving = false,
                        apiKey = "",
                        editingConnectionId = null,
                        error = null,
                        testModelCount = null,
                    )
                }
                onSaved()
            }.onFailure { error ->
                _state.update { it.copy(isSaving = false, error = error.message ?: "Не удалось сохранить подключение.") }
            }
        }
    }

    fun setActive(id: String?) {
        viewModelScope.launch {
            runCatching { repository.setActiveConnection(id) }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    fun setLegacyCoarenaActive() = setActive(null)

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { repository.deleteConnection(id) }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }
}
