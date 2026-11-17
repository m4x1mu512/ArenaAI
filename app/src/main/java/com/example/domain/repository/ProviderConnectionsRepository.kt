package com.example.domain.repository

import com.example.domain.model.ProviderConnection
import kotlinx.coroutines.flow.Flow

/**
 * Persists non-secret provider connection metadata and delegates API-key storage to
 * the Android Keystore-backed credential vault.
 */
interface ProviderConnectionsRepository {
    val connections: Flow<List<ProviderConnection>>
    val activeConnectionId: Flow<String?>

    suspend fun addConnection(connection: ProviderConnection, apiKey: String, makeActive: Boolean = true)
    suspend fun updateConnection(connection: ProviderConnection, replacementApiKey: String? = null)
    suspend fun setActiveConnection(connectionId: String?)
    suspend fun deleteConnection(connectionId: String)
    suspend fun clearAllConnections()
    suspend fun getActiveConnection(): ProviderConnection?
    suspend fun getApiKey(connection: ProviderConnection): String?
}
