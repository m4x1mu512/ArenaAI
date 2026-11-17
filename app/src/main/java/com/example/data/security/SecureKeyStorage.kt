package com.example.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Abstraction for encrypted local storage of the user's API key.
 */
interface SecureKeyStorage {
    /** Saves [apiKey] encrypted on device. */
    suspend fun saveApiKey(apiKey: String)

    /** Returns the decrypted API key, or null if none is stored. */
    suspend fun getApiKey(): String?

    /** Returns true if an encrypted API key currently exists on device. */
    suspend fun hasApiKey(): Boolean

    /** Permanently deletes the stored API key from device storage. */
    suspend fun deleteApiKey()
}

/**
 * Stores the API key encrypted with AES-256-GCM backed by Android Keystore.
 * Ciphertext and IV are persisted in private app preferences; plaintext is never written to disk.
 */
class AndroidKeystoreSecureStorage(
    context: Context,
) : SecureKeyStorage, ProviderCredentialStorage {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun saveApiKey(apiKey: String) = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            deleteApiKey()
            return@withContext
        }
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))

        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val cipherBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit()
            .putString(KEY_IV, ivBase64)
            .putString(KEY_CIPHERTEXT, cipherBase64)
            .commit()
        Unit
    }

    override suspend fun getApiKey(): String? = withContext(Dispatchers.IO) {
        val ivBase64 = prefs.getString(KEY_IV, null) ?: return@withContext null
        val cipherBase64 = prefs.getString(KEY_CIPHERTEXT, null) ?: return@withContext null
        runCatching {
            val secretKey = getExistingSecretKey() ?: return@withContext null
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val encrypted = Base64.decode(cipherBase64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    override suspend fun hasApiKey(): Boolean = withContext(Dispatchers.IO) {
        prefs.contains(KEY_CIPHERTEXT) && prefs.contains(KEY_IV)
    }

    override suspend fun deleteApiKey() = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(KEY_IV)
            .remove(KEY_CIPHERTEXT)
            .commit()
        Unit
    }


    override suspend fun saveProviderApiKey(credentialId: String, apiKey: String) = withContext(Dispatchers.IO) {
        require(credentialId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid credential ID" }
        val trimmed = apiKey.trim()
        require(trimmed.isNotEmpty()) { "API key cannot be empty" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        cipher.updateAAD(credentialId.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
        val prefix = "provider_${credentialId}_"
        val saved = prefs.edit()
            .putString(prefix + "iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(prefix + "ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
        check(saved) { "Unable to save provider credential" }
    }

    override suspend fun getProviderApiKey(credentialId: String): String? = withContext(Dispatchers.IO) {
        if (!credentialId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return@withContext null
        val prefix = "provider_${credentialId}_"
        val ivText = prefs.getString(prefix + "iv", null) ?: return@withContext null
        val encryptedText = prefs.getString(prefix + "ciphertext", null) ?: return@withContext null
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getExistingSecretKey() ?: return@withContext null,
                GCMParameterSpec(GCM_TAG_LENGTH, Base64.decode(ivText, Base64.NO_WRAP)),
            )
            cipher.updateAAD(credentialId.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(Base64.decode(encryptedText, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    override suspend fun deleteProviderApiKey(credentialId: String) = withContext(Dispatchers.IO) {
        require(credentialId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid credential ID" }
        val prefix = "provider_${credentialId}_"
        prefs.edit()
            .remove(prefix + "iv")
            .remove(prefix + "ciphertext")
            .commit()
        Unit
    }

    private fun getExistingSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun getOrCreateSecretKey(): SecretKey {
        getExistingSecretKey()?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "coarena_chat_api_key_aes256"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH = 128
        const val PREFS_NAME = "coarena_encrypted_vault"
        const val KEY_IV = "key_iv"
        const val KEY_CIPHERTEXT = "key_ciphertext"
    }
}
