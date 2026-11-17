package com.example.data.local

import com.example.data.remote.CoarenaUrlBuilder
import com.example.data.security.ProviderCredentialStorage
import com.example.domain.model.ProviderConnection
import com.example.domain.repository.ProviderConnectionsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderConnectionsRepositoryImpl @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val credentialStorage: ProviderCredentialStorage,
) : ProviderConnectionsRepository {

    override val connections: Flow<List<ProviderConnection>> =
        preferencesManager.providerConnectionsFlow

    override val activeConnectionId: Flow<String?> =
        preferencesManager.activeProviderConnectionIdFlow

    override suspend fun addConnection(
        connection: ProviderConnection,
        apiKey: String,
        makeActive: Boolean,
    ) {
        require(apiKey.isNotBlank()) { "API key cannot be empty" }
        require(CoarenaUrlBuilder.isValidBaseUrl(connection.baseUrl)) { "Invalid Base URL" }
        require(connection.id.matches(ID_PATTERN)) { "Invalid connection ID" }
        require(connection.credentialId.matches(ID_PATTERN)) { "Invalid credential ID" }

        credentialStorage.saveProviderApiKey(connection.credentialId, apiKey)
        val current = connections.first()
        require(current.none { it.id == connection.id }) { "Connection already exists" }
        preferencesManager.setProviderConnections(current + connection.copy(baseUrl = CoarenaUrlBuilder.normalizeBaseUrl(connection.baseUrl)))
        if (makeActive || activeConnectionId.first() == null) {
            preferencesManager.setActiveProviderConnectionId(connection.id)
        }
    }

    override suspend fun updateConnection(
        connection: ProviderConnection,
        replacementApiKey: String?,
    ) {
        require(CoarenaUrlBuilder.isValidBaseUrl(connection.baseUrl)) { "Invalid Base URL" }
        val current = connections.first()
        require(current.any { it.id == connection.id }) { "Connection not found" }
        if (!replacementApiKey.isNullOrBlank()) {
            credentialStorage.saveProviderApiKey(connection.credentialId, replacementApiKey)
        }
        preferencesManager.setProviderConnections(
            current.map {
                if (it.id == connection.id) {
                    connection.copy(baseUrl = CoarenaUrlBuilder.normalizeBaseUrl(connection.baseUrl))
                } else it
            },
        )
    }

    override suspend fun setActiveConnection(connectionId: String?) {
        if (connectionId != null) {
            require(connections.first().any { it.id == connectionId && it.isEnabled }) {
                "Connection is unavailable"
            }
        }
        preferencesManager.setActiveProviderConnectionId(connectionId)
    }

    override suspend fun deleteConnection(connectionId: String) {
        val current = connections.first()
        val removed = current.firstOrNull { it.id == connectionId } ?: return
        val remaining = current.filterNot { it.id == connectionId }
        preferencesManager.setProviderConnections(remaining)
        credentialStorage.deleteProviderApiKey(removed.credentialId)
        if (activeConnectionId.first() == connectionId) {
            preferencesManager.setActiveProviderConnectionId(remaining.firstOrNull { it.isEnabled }?.id)
        }
    }

    override suspend fun getActiveConnection(): ProviderConnection? {
        val activeId = activeConnectionId.first() ?: return null
        return connections.first().firstOrNull { it.id == activeId && it.isEnabled }
    }

    override suspend fun getApiKey(connection: ProviderConnection): String? =
        credentialStorage.getProviderApiKey(connection.credentialId)

    private companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,80}")
    }
}
