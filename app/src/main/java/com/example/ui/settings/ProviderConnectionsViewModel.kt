package com.example.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.remote.CoarenaNetworkClient
import com.example.data.remote.AnthropicNetworkClient
import com.example.domain.model.ProviderCatalog
import com.example.domain.model.ProviderConnection
import com.example.domain.repository.ProviderConnectionsRepository
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
    val apiKey: String = "",
    val isTesting: Boolean = false,
    val testModelCount: Int? = null,
    val error: String? = null,
    val isSaving: Boolean = false,
    val editingConnectionId: String? = null,
)

@HiltViewModel
class ProviderConnectionsViewModel @Inject constructor(
    private val repository: ProviderConnectionsRepository,
    private val networkClient: CoarenaNetworkClient,
    private val anthropicNetworkClient: AnthropicNetworkClient,
) : ViewModel() {
    private val _state = MutableStateFlow(ProviderConnectionsUiState())
    val state: StateFlow<ProviderConnectionsUiState> = _state.asStateFlow()

    init {
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

    fun saveConnection() {
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
