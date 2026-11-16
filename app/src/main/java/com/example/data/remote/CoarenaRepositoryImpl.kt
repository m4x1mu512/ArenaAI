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
) : CoarenaRepository {

    override val lastRequestDiagnostic: StateFlow<RequestDiagnostic?> =
        networkClient.lastRequestDiagnostic

    override suspend fun fetchModels(): Result<List<AiModel>> {
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
        val apiKey = settingsRepository.getApiKey()?.trim()
        if (apiKey.isNullOrEmpty()) {
            return Result.failure(CoarenaApiException(AppError.MissingApiKey()))
        }
        val settings = settingsRepository.settingsFlow.first()
        return Result.success(settings to apiKey)
    }
}
