package com.example.data.remote

import com.example.domain.model.AiModel
import com.example.domain.model.AppError
import com.example.domain.model.AppSettings
import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatStreamEvent
import com.example.domain.model.ConnectionTestResult
import com.example.domain.model.CreditsInfo
import com.example.domain.model.LimitsInfo
import com.example.domain.model.RequestDiagnostic
import com.example.domain.model.RequestHistoryItem
import com.example.domain.repository.CoarenaRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.repository.ProviderConnectionsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [CoarenaRepository] implementation coordinating saved credentials and [CoarenaNetworkClient].
 */
@Singleton
class CoarenaRepositoryImpl @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val networkClient: CoarenaNetworkClient,
    private val providerConnectionsRepository: ProviderConnectionsRepository,
    private val anthropicNetworkClient: AnthropicNetworkClient,
) : CoarenaRepository {

    override val activeProviderConnectionId = providerConnectionsRepository.activeConnectionId

    override suspend fun getSelectedModelId(): String? {
        val active = providerConnectionsRepository.getActiveConnection()
        return active?.defaultModelId ?: if (active == null) settingsRepository.settingsFlow.first().defaultModelId else null
    }

    override suspend fun saveSelectedModel(modelId: String) {
        val active = providerConnectionsRepository.getActiveConnection()
        if (active != null) {
            providerConnectionsRepository.updateConnection(active.copy(defaultModelId = modelId))
        } else {
            settingsRepository.saveDefaultModel(modelId)
        }
    }

    override val lastRequestDiagnostic: StateFlow<RequestDiagnostic?> =
        networkClient.lastRequestDiagnostic

    override suspend fun fetchModels(): Result<List<AiModel>> {
        val active = providerConnectionsRepository.getActiveConnection()
        if (active != null) {
            val key = providerConnectionsRepository.getApiKey(active)?.takeIf { it.isNotBlank() }
                ?: return Result.failure(CoarenaApiException(AppError.MissingApiKey()))
            return if (active.providerId == "anthropic") {
                anthropicNetworkClient.fetchModels(active.baseUrl, key)
            } else {
                networkClient.fetchModels(active.baseUrl, key)
            }
        }
        val (settings, apiKey) = requireCredentialsAndSettings().getOrElse { return Result.failure(it) }
        return networkClient.fetchModels(settings.baseUrl, apiKey)
    }

    override suspend fun testConnection(apiKey: String, baseUrl: String): ConnectionTestResult {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return ConnectionTestResult(isSuccess = false, error = AppError.MissingApiKey())
        }
        if (!CoarenaUrlBuilder.isValidBaseUrl(baseUrl)) {
            return ConnectionTestResult(
                isSuccess = false,
                error = AppError.InvalidUrl("Invalid Base URL"),
            )
        }
        val result = networkClient.fetchModels(baseUrl, trimmedKey)
        return result.fold(
            onSuccess = { models ->
                ConnectionTestResult(isSuccess = true, modelCount = models.size)
            },
            onFailure = { throwable ->
                val error = (throwable as? CoarenaApiException)?.appError
                    ?: AppError.Unknown(throwable.message ?: "Connection failed")
                ConnectionTestResult(isSuccess = false, error = error)
            },
        )
    }

    override fun sendChatMessage(
        modelId: String,
        history: List<ChatMessage>,
        systemPrompt: String,
    ): Flow<ChatStreamEvent> = flow {
        val active = providerConnectionsRepository.getActiveConnection()
        if (active != null) {
            val key = providerConnectionsRepository.getApiKey(active)?.takeIf { it.isNotBlank() }
                ?: throw CoarenaApiException(AppError.MissingApiKey())
            val settings = settingsRepository.settingsFlow.first()
            val events = if (active.providerId == "anthropic") {
                anthropicNetworkClient.streamChat(
                    baseUrl = active.baseUrl,
                    apiKey = key,
                    modelId = modelId,
                    history = history,
                    systemPrompt = systemPrompt,
                    useStreaming = settings.useStreaming,
                )
            } else {
                networkClient.streamChatCompletion(
                    baseUrl = active.baseUrl,
                    apiKey = key,
                    modelId = modelId,
                    history = history,
                    systemPrompt = systemPrompt,
                    useStreaming = settings.useStreaming,
                    wireFormat = active.wireFormat,
                )
            }
            emitAll(events)
        } else {
            val (settings, apiKey) = requireCredentialsAndSettings().getOrThrow()
            emitAll(
                networkClient.streamChatCompletion(
                    baseUrl = settings.baseUrl,
                    apiKey = apiKey,
                    modelId = modelId,
                    history = history,
                    systemPrompt = systemPrompt,
                    useStreaming = settings.useStreaming,
                    wireFormat = settings.wireFormat,
                ),
            )
        }
    }

    override suspend fun fetchCredits(): Result<CreditsInfo> {
        val (settings, apiKey) = requireCredentialsAndSettings().getOrElse { return Result.failure(it) }
        return networkClient.fetchCredits(settings.baseUrl, apiKey)
    }

    override suspend fun fetchLimits(): Result<LimitsInfo> {
        val (settings, apiKey) = requireCredentialsAndSettings().getOrElse { return Result.failure(it) }
        return networkClient.fetchLimits(settings.baseUrl, apiKey)
    }

    override suspend fun fetchRequests(): Result<List<RequestHistoryItem>> {
        val (settings, apiKey) = requireCredentialsAndSettings().getOrElse { return Result.failure(it) }
        return networkClient.fetchRequests(settings.baseUrl, apiKey)
    }

    private suspend fun requireCredentialsAndSettings(): Result<Pair<AppSettings, String>> {
        if (providerConnectionsRepository.getActiveConnection() != null) {
            return Result.failure(IllegalStateException("Данные аккаунта доступны только для legacy Coarena-подключения."))
        }
        val apiKey = settingsRepository.getApiKey()?.trim()
        if (apiKey.isNullOrEmpty()) {
            return Result.failure(CoarenaApiException(AppError.MissingApiKey()))
        }
        val settings = settingsRepository.settingsFlow.first()
        return Result.success(settings to apiKey)
    }
}
