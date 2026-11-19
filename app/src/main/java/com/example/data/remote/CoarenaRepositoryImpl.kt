package com.example.data.remote

import com.example.domain.model.AiModel
import com.example.domain.model.AppError
import com.example.domain.model.AppSettings
import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatStreamEvent
import com.example.domain.model.ConnectionTestResult
import com.example.domain.model.CreditsInfo
import com.example.domain.model.GeneratedImage
import com.example.domain.model.LimitsInfo
import com.example.domain.model.RequestDiagnostic
import com.example.domain.model.RequestHistoryItem
import com.example.domain.model.ProviderCatalog
import com.example.domain.repository.CoarenaRepository
import com.example.domain.repository.SettingsRepository
import com.example.domain.repository.ProviderConnectionsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
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
    override val activeProviderId = combine(
        providerConnectionsRepository.connections,
        providerConnectionsRepository.activeConnectionId,
    ) { connections, activeId ->
        connections.firstOrNull { it.id == activeId && it.isEnabled }?.providerId
    }

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

    override suspend fun activateProviderConnection(connectionId: String?) {
        providerConnectionsRepository.setActiveConnection(connectionId)
    }

    override suspend fun fetchModelsFromAllProviders(): Result<List<AiModel>> {
        val connections = providerConnectionsRepository.connections.first().filter { it.isEnabled }
        if (connections.isEmpty()) return fetchModels()
        val results = connections.mapNotNull { connection ->
            runCatching {
                val key = providerConnectionsRepository.getApiKey(connection)?.takeIf { it.isNotBlank() }
                    ?: throw CoarenaApiException(AppError.MissingApiKey())
                val fetched = when (connection.providerId) {
                    "anthropic" -> anthropicNetworkClient.fetchModels(connection.baseUrl, key)
                    "cloudflare" -> {
                        val accountId = Regex("/accounts/([^/]+)/ai/v1").find(connection.baseUrl)?.groupValues?.getOrNull(1).orEmpty()
                        networkClient.fetchCloudflareModels(accountId, key)
                    }
                    else -> networkClient.fetchModels(connection.baseUrl, key)
                }.getOrThrow()
                val manualIds = buildList {
                    connection.manualModelIds.orEmpty().split(',', '\\n', ';').map(String::trim)
                        .filter(String::isNotBlank).forEach(::add)
                    connection.defaultModelId?.takeIf(String::isNotBlank)?.let(::add)
                }.distinct().let { ids ->
                    if (connection.providerId == "cloudflare") ids.filterNot {
                        it.equals("@cf/black-forest-labs/flux-1-schnell", ignoreCase = true)
                    } else ids
                }
                val merged = manualIds.fold(fetched) { current, modelId ->
                    if (current.any { it.id == modelId }) current
                    else current + AiModel(id = modelId, displayName = modelId, ownedBy = "Manual")
                }
                val providerName = ProviderCatalog.find(connection.providerId)?.displayName ?: connection.displayName
                merged.map { it.copy(providerConnectionId = connection.id, providerDisplayName = providerName) }
            }.getOrNull()
        }
        if (results.isEmpty()) return Result.failure(
            IllegalStateException("Не удалось загрузить модели ни одного подключённого провайдера. Проверьте API-ключи и адреса API.")
        )
        return Result.success(results.flatten())
    }

    override suspend fun fetchModels(): Result<List<AiModel>> {
        val active = providerConnectionsRepository.getActiveConnection()
        if (active != null) {
            val key = providerConnectionsRepository.getApiKey(active)?.takeIf { it.isNotBlank() }
                ?: return Result.failure(CoarenaApiException(AppError.MissingApiKey()))
            val fetched = when (active.providerId) {
                "anthropic" -> anthropicNetworkClient.fetchModels(active.baseUrl, key)
                "cloudflare" -> {
                    val accountId = Regex("/accounts/([^/]+)/ai/v1").find(active.baseUrl)?.groupValues?.getOrNull(1).orEmpty()
                    networkClient.fetchCloudflareModels(accountId, key)
                }
                else -> networkClient.fetchModels(active.baseUrl, key)
            }
            return fetched.map { models ->
                val manualIds = buildList {
                    active.manualModelIds.orEmpty().split(',', '\n', ';').map(String::trim)
                        .filter(String::isNotBlank).forEach { add(it) }
                    active.defaultModelId?.takeIf { it.isNotBlank() }?.let(::add)
                }.distinct()
                val chatManualIds = if (active.providerId == "cloudflare") {
                    manualIds.filterNot {
                        it.equals("@cf/black-forest-labs/flux-1-schnell", ignoreCase = true)
                    }
                } else {
                    manualIds
                }
                chatManualIds.fold(models) { current, modelId ->
                    if (current.any { it.id == modelId }) current
                    else current + AiModel(id = modelId, displayName = modelId, ownedBy = "AI Gateway")
                }
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
                    requestHeaders = if (active.providerId == "cloudflare") {
                        mapOf("cf-aig-gateway-id" to (active.gatewayId?.takeIf { it.isNotBlank() } ?: "default"))
                    } else emptyMap(),
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

    override suspend fun generateImage(prompt: String): Result<GeneratedImage> {
        val active = providerConnectionsRepository.getActiveConnection()
            ?: return Result.failure(IllegalStateException("Для генерации изображений подключите Cloudflare AI в настройках."))
        if (active.providerId != "cloudflare") {
            return Result.failure(IllegalStateException("Генерация изображений FLUX.1 доступна через подключение Cloudflare AI."))
        }
        val key = providerConnectionsRepository.getApiKey(active)?.takeIf { it.isNotBlank() }
            ?: return Result.failure(CoarenaApiException(AppError.MissingApiKey()))
        val accountId = Regex("/accounts/([^/]+)/ai/v1").find(active.baseUrl)?.groupValues?.getOrNull(1).orEmpty()
        return networkClient.generateCloudflareImage(
            accountId = accountId,
            apiKey = key,
            prompt = prompt,
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
