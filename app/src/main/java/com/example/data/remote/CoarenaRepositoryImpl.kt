package com.example.data.remote

import com.example.domain.model.AiModel
import com.example.domain.model.AppError
import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatStreamEvent
import com.example.domain.model.ConnectionTestResult
import com.example.domain.model.CreditsInfo
import com.example.domain.model.LimitsInfo
import com.example.domain.model.RequestHistoryItem
import com.example.domain.repository.CoarenaRepository
import com.example.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
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

    override suspend fun fetchModels(): Result<List<AiModel>> {
        val (baseUrl, apiKey) = requireCredentials().getOrElse { return Result.failure(it) }
        return networkClient.fetchModels(baseUrl, apiKey)
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
        val (baseUrl, apiKey) = requireCredentials().getOrThrow()
        emitAll(
            networkClient.streamChatCompletion(
                baseUrl = baseUrl,
                apiKey = apiKey,
                modelId = modelId,
                history = history,
                systemPrompt = systemPrompt,
            ),
        )
    }

    override suspend fun fetchCredits(): Result<CreditsInfo> {
        val (baseUrl, apiKey) = requireCredentials().getOrElse { return Result.failure(it) }
        return networkClient.fetchCredits(baseUrl, apiKey)
    }

    override suspend fun fetchLimits(): Result<LimitsInfo> {
        val (baseUrl, apiKey) = requireCredentials().getOrElse { return Result.failure(it) }
        return networkClient.fetchLimits(baseUrl, apiKey)
    }

    override suspend fun fetchRequests(): Result<List<RequestHistoryItem>> {
        val (baseUrl, apiKey) = requireCredentials().getOrElse { return Result.failure(it) }
        return networkClient.fetchRequests(baseUrl, apiKey)
    }

    private suspend fun requireCredentials(): Result<Pair<String, String>> {
        val apiKey = settingsRepository.getApiKey()?.trim()
        if (apiKey.isNullOrEmpty()) {
            return Result.failure(CoarenaApiException(AppError.MissingApiKey()))
        }
        val settings = settingsRepository.settingsFlow.first()
        return Result.success(settings.baseUrl to apiKey)
    }
}
