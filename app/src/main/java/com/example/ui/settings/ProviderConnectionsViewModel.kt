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
import kotlinx.coroutines.CancellationException
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
    val connectionTestMessages: Map<String, String> = emptyMap(),
    val connectionTestingIds: Set<String> = emptySet(),
    val activeConnectionId: String? = null,
    val selectedPresetId: String = ProviderCatalog.presets.first().id,
    val displayName: String = ProviderCatalog.presets.first().displayName,
    val baseUrl: String = ProviderCatalog.presets.first().baseUrl,
    val accountId: String = "",
    val gatewayId: String = "default",
    val manualModelIds: String = "",
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
                manualModelIds = "",
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
                manualModelIds = connection.manualModelIds.orEmpty(),
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
    fun setManualModelIds(value: String) = _state.update { it.copy(manualModelIds = value, error = null) }
    fun setApiKey(value: String) = _state.update { it.copy(apiKey = value, error = null, testModelCount = null) }

    fun testConnection() {
        val snapshot = _state.value
        if (snapshot.selectedPresetId == "cloudflare" && !snapshot.accountId.matches(Regex("[A-Fa-f0-9]{32}"))) {
            _state.update { it.copy(error = "Укажите корректный Cloudflare Account ID (32 символа).") }
            return
        }
        if (snapshot.selectedPresetId != "cloudflare" && snapshot.baseUrl.isBlank()) {
            _state.update { it.copy(error = "Укажите Base URL.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isTesting = true, error = null, testModelCount = null) }
            try {
                val existing = snapshot.editingConnectionId?.let { id ->
                    snapshot.connections.firstOrNull { it.id == id }
                }
                val effectiveKey = snapshot.apiKey.trim().ifBlank {
                    existing?.let { repository.getApiKey(it) }.orEmpty()
                }
                check(effectiveKey.isNotBlank()) {
                    if (snapshot.selectedPresetId == "cloudflare") {
                        "Укажите Cloudflare API Token."
                    } else {
                        "Укажите API Key."
                    }
                }
                val result = when (snapshot.selectedPresetId) {
                    "anthropic" -> anthropicNetworkClient.fetchModels(snapshot.baseUrl.trim(), effectiveKey)
                    "cloudflare" -> networkClient.fetchCloudflareModels(snapshot.accountId, effectiveKey)
                    else -> networkClient.fetchModels(snapshot.baseUrl.trim(), effectiveKey)
                }
                val models = result.getOrElse { throw it }
                _state.update {
                    it.copy(
                        isTesting = false,
                        testModelCount = models.size,
                        error = if (models.isEmpty()) {
                            "Подключение отвечает, но список моделей пуст. Проверьте доступ к каталогу моделей или укажите ID моделей вручную."
                        } else null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        isTesting = false,
                        testModelCount = null,
                        error = error.message?.take(500) ?: "Не удалось проверить подключение.",
                    )
                }
            }
        }
    }

    fun saveConnection(onSaved: () -> Unit = {}) {
        val snapshot = _state.value
        val resolvedBaseUrl = if (snapshot.selectedPresetId == "cloudflare") {
            "https://api.cloudflare.com/client/v4/accounts/${snapshot.accountId.trim()}/ai/v1"
        } else snapshot.baseUrl.trim()
        if (snapshot.displayName.isBlank() || resolvedBaseUrl.isBlank() ||
            (snapshot.selectedPresetId == "cloudflare" && !snapshot.accountId.matches(Regex("[A-Fa-f0-9]{32}"))) ||
            (snapshot.apiKey.isBlank() && snapshot.editingConnectionId == null)
        ) {
            _state.update { it.copy(error = "Заполните название, Account ID и API Token (для Cloudflare) или Base URL и API Key.") }
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
                            baseUrl = resolvedBaseUrl,
                            displayName = snapshot.displayName.trim(),
                            gatewayId = snapshot.gatewayId.takeIf { snapshot.selectedPresetId == "cloudflare" },
                            manualModelIds = snapshot.manualModelIds.trim().takeIf { it.isNotBlank() },
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
                            baseUrl = resolvedBaseUrl,
                            credentialId = "provider_$id",
                            displayName = snapshot.displayName.trim(),
                            gatewayId = snapshot.gatewayId.takeIf { snapshot.selectedPresetId == "cloudflare" },
                            manualModelIds = snapshot.manualModelIds.trim().takeIf { it.isNotBlank() },
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

    fun testSavedConnection(connection: ProviderConnection) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    connectionTestingIds = it.connectionTestingIds + connection.id,
                    connectionTestMessages = it.connectionTestMessages + (connection.id to "Проверка подключения…"),
                )
            }
            try {
                val key = repository.getApiKey(connection)?.takeIf { it.isNotBlank() }
                    ?: error("Для этого подключения не найден API Key. Откройте «Изменить» и сохраните ключ заново.")
                val result = when (connection.providerId) {
                    "anthropic" -> anthropicNetworkClient.fetchModels(connection.baseUrl, key)
                    "cloudflare" -> {
                        val accountId = Regex("/accounts/([^/]+)/ai/v1")
                            .find(connection.baseUrl)?.groupValues?.getOrNull(1).orEmpty()
                        networkClient.fetchCloudflareModels(accountId, key)
                    }
                    else -> networkClient.fetchModels(connection.baseUrl, key)
                }
                val models = result.getOrElse { throw it }
                val message = if (models.isEmpty()) {
                    "Подключение отвечает, но каталог моделей пуст."
                } else {
                    "Подключение работает. Найдено моделей: ${models.size}"
                }
                _state.update { it.copy(connectionTestMessages = it.connectionTestMessages + (connection.id to message)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        connectionTestMessages = it.connectionTestMessages +
                            (connection.id to (error.message?.take(500) ?: "Не удалось проверить подключение.")),
                    )
                }
            } finally {
                _state.update { it.copy(connectionTestingIds = it.connectionTestingIds - connection.id) }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { repository.deleteConnection(id) }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }
}
