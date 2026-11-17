package com.example.data.security

/**
 * Secure storage for multiple provider credentials.
 *
 * Implementations must encrypt values at rest and must not log plaintext keys.
 */
interface ProviderCredentialStorage {
    suspend fun saveProviderApiKey(credentialId: String, apiKey: String)
    suspend fun getProviderApiKey(credentialId: String): String?
    suspend fun deleteProviderApiKey(credentialId: String)
}
