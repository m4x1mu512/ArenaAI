package com.example.data.remote

import com.example.data.security.SecretRedactor
import com.example.domain.model.AiModel
import com.example.domain.model.ApiWireFormat
import com.example.domain.model.AppError
import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatStreamEvent
import com.example.domain.model.CreditsInfo
import com.example.domain.model.LimitsInfo
import com.example.domain.model.MessageRole
import com.example.domain.model.RequestDiagnostic
import com.example.domain.model.RequestHistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import okhttp3.ResponseBody
import java.io.File
import java.util.Base64
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network client executing Coarena REST and SSE streaming requests with automatic
 * non-streaming fallback, HTTP error mapping, and strict API key redaction.
 */
@Singleton
class CoarenaNetworkClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val retrofitService: CoarenaRetrofitService,
    private val parser: CoarenaApiParser,
    private val json: Json,
) {

    private val _lastRequestDiagnostic = MutableStateFlow<RequestDiagnostic?>(null)

    private companion object { const val MAX_MULTIMODAL_IMAGE_BYTES = 5L * 1024L * 1024L }

    /** Latest non-secret request diagnostic snapshot for troubleshooting. */
    val lastRequestDiagnostic: StateFlow<RequestDiagnostic?> = _lastRequestDiagnostic.asStateFlow()

    /**
     * Fetches available models from `{baseUrl}/models`.
     */
    suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<AiModel>> {
        return executeGet(baseUrl, "models", apiKey) { body ->
            parser.parseModels(body)
        }
    }

    /**
     * Fetches the account's Workers AI model catalog from Cloudflare.
     * Third-party AI Gateway model IDs can be supplied manually in connection settings.
     */
    suspend fun fetchCloudflareModels(accountId: String, apiKey: String): Result<List<AiModel>> =
        withContext(Dispatchers.IO) {
            val trimmedAccountId = accountId.trim()
            val trimmedKey = apiKey.trim()
            if (!trimmedAccountId.matches(Regex("[A-Fa-f0-9]{32}"))) {
                return@withContext Result.failure(IllegalArgumentException("Некорректный Cloudflare Account ID."))
            }
            if (trimmedKey.isEmpty()) {
                return@withContext Result.failure(CoarenaApiException(AppError.MissingApiKey()))
            }
            val url = "https://api.cloudflare.com/client/v4/accounts/$trimmedAccountId/ai/models/search?task=text-generation&per_page=100&hide_experimental=true"
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $trimmedKey")
                    .header("Accept", "application/json")
                    .get()
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            CoarenaApiException(mapHttpStatus(response.code, raw, trimmedKey)),
                        )
                    }
                    runCatching { parser.parseModels(raw) }
                        .fold(
                            onSuccess = { Result.success(it) },
                            onFailure = { Result.failure(mapException(it, trimmedKey)) },
                        )
                }
            } catch (error: Exception) {
                Result.failure(mapException(error, trimmedKey))
            }
        }

    /**
     * Fetches account credits from `{baseUrl}/credits`.
     */
    suspend fun fetchCredits(baseUrl: String, apiKey: String): Result<CreditsInfo> {
        return executeGet(baseUrl, "credits", apiKey) { body ->
            parser.parseCredits(body)
        }
    }

    /**
     * Fetches account limits from `{baseUrl}/limits`.
     */
    suspend fun fetchLimits(baseUrl: String, apiKey: String): Result<LimitsInfo> {
        return executeGet(baseUrl, "limits", apiKey) { body ->
            parser.parseLimits(body)
        }
    }

    /**
     * Fetches recent request history from `{baseUrl}/requests`.
     */
    suspend fun fetchRequests(baseUrl: String, apiKey: String): Result<List<RequestHistoryItem>> {
        return executeGet(baseUrl, "requests", apiKey) { body ->
            parser.parseRequests(body)
        }
    }

    /**
     * Sends chat messages to `{baseUrl}/chat/completions` or `{baseUrl}/responses`.
     * When [useStreaming] is true, streams SSE deltas and falls back to `stream: false`
     * if streaming fails before emitting tokens or if the endpoint returns standard JSON.
     */
    fun streamChatCompletion(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        history: List<ChatMessage>,
        systemPrompt: String,
        useStreaming: Boolean = true,
        wireFormat: ApiWireFormat = ApiWireFormat.CHAT_COMPLETIONS,
        requestHeaders: Map<String, String> = emptyMap(),
    ): Flow<ChatStreamEvent> = flow {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            throw CoarenaApiException(AppError.MissingApiKey())
        }
        val trimmedModelId = modelId.trim()
        val endpointPath = if (wireFormat == ApiWireFormat.RESPONSES) {
            "responses"
        } else {
            "chat/completions"
        }
        val endpoint = CoarenaUrlBuilder.requireValidEndpoint(baseUrl, endpointPath)
            .getOrThrow()

        val conversationMessages = history
            .filter { !it.isError && (it.content.isNotBlank() || it.attachments.isNotEmpty()) }
        val hasImages = conversationMessages.any { it.attachments.isNotEmpty() }
        val chatMessagesWithSystem = buildList {
            if (systemPrompt.isNotBlank()) {
                add(ChatMessageDto(role = MessageRole.SYSTEM.apiValue, content = systemPrompt))
            }
            addAll(conversationMessages.map { msg ->
                ChatMessageDto(role = msg.role.apiValue, content = msg.content)
            })
        }

        fun encodePayload(stream: Boolean): String {
            if (!hasImages) {
                return if (wireFormat == ApiWireFormat.RESPONSES) {
                    json.encodeToString(
                        ResponsesRequestDto(
                            model = trimmedModelId,
                            input = conversationMessages.map { ChatMessageDto(it.role.apiValue, it.content) },
                            instructions = systemPrompt.takeIf { it.isNotBlank() },
                            stream = stream,
                        ),
                    )
                } else {
                    json.encodeToString(
                        ChatCompletionRequestDto(
                            model = trimmedModelId,
                            messages = chatMessagesWithSystem,
                            stream = stream,
                        ),
                    )
                }
            }

            var totalImageBytes = 0L
            fun imageDataUrl(localPath: String, mimeType: String): String {
                val file = File(localPath)
                if (!file.isFile) throw IOException("An attached image is no longer available")
                totalImageBytes += file.length()
                if (totalImageBytes > MAX_MULTIMODAL_IMAGE_BYTES) {
                    throw IOException("Attached images exceed the 5 MiB request limit")
                }
                val bytes = file.readBytes()
                return "data:$mimeType;base64," + Base64.getEncoder().encodeToString(bytes)
            }

            return if (wireFormat == ApiWireFormat.RESPONSES) {
                val inputs = buildList {
                    if (systemPrompt.isNotBlank()) {
                        add(ResponsesInputMessageDto(
                            role = MessageRole.SYSTEM.apiValue,
                            content = listOf(ResponsesInputPartDto(type = "input_text", text = systemPrompt)),
                        ))
                    }
                    conversationMessages.forEach { msg ->
                        val parts = buildList {
                            if (msg.content.isNotBlank()) {
                                add(ResponsesInputPartDto(type = "input_text", text = msg.content))
                            }
                            msg.attachments.forEach { attachment ->
                                add(ResponsesInputPartDto(
                                    type = "input_image",
                                    imageUrl = imageDataUrl(attachment.localPath, attachment.mimeType),
                                ))
                            }
                        }
                        add(ResponsesInputMessageDto(role = msg.role.apiValue, content = parts))
                    }
                }
                json.encodeToString(
                    ResponsesMultimodalRequestDto(
                        model = trimmedModelId,
                        input = inputs,
                        instructions = null,
                        stream = stream,
                    ),
                )
            } else {
                val messages = buildList {
                    if (systemPrompt.isNotBlank()) {
                        add(MultimodalChatMessageDto(
                            role = MessageRole.SYSTEM.apiValue,
                            content = listOf(MultimodalContentPartDto(type = "text", text = systemPrompt)),
                        ))
                    }
                    conversationMessages.forEach { msg ->
                        val parts = buildList {
                            if (msg.content.isNotBlank()) {
                                add(MultimodalContentPartDto(type = "text", text = msg.content))
                            }
                            msg.attachments.forEach { attachment ->
                                add(MultimodalContentPartDto(
                                    type = "image_url",
                                    imageUrl = ImageUrlDto(imageDataUrl(attachment.localPath, attachment.mimeType)),
                                ))
                            }
                        }
                        add(MultimodalChatMessageDto(role = msg.role.apiValue, content = parts))
                    }
                }
                json.encodeToString(
                    ChatCompletionMultimodalRequestDto(
                        model = trimmedModelId,
                        messages = messages,
                        stream = stream,
                    ),
                )
            }
        }

        _lastRequestDiagnostic.value = RequestDiagnostic(
            endpointUrl = endpoint,
            sentModelId = trimmedModelId,
            stream = useStreaming,
            wireFormat = wireFormat,
        )

        if (!useStreaming) {
            val nonStreamingResult = executeNonStreamingJson(
                endpoint = endpoint,
                apiKey = trimmedKey,
                requestBodyJson = encodePayload(stream = false),
                sentModelId = trimmedModelId,
                wireFormat = wireFormat,
                requestHeaders = requestHeaders,
            )
            nonStreamingResult.fold(
                onSuccess = { completed -> emit(completed) },
                onFailure = { err ->
                    throw (err as? CoarenaApiException) ?: mapException(err, trimmedKey)
                },
            )
            return@flow
        }

        var emittedAnyDelta = false
        var lastTotalTokens: Int? = null
        var shouldFallbackToNonStreaming = false
        var streamingFailure: CoarenaApiException? = null

        try {
            val requestBodyJson = encodePayload(stream = true)
            val okRequest = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $trimmedKey")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .apply { requestHeaders.forEach { (name, value) -> header(name, value) } }
                .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(okRequest).execute().use { response ->
                val statusCode = response.code
                val headerModelId = response.header("x-model")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: response.header("x-coarena-model")?.trim()?.takeIf { it.isNotEmpty() }

                _lastRequestDiagnostic.value = RequestDiagnostic(
                    endpointUrl = endpoint,
                    sentModelId = trimmedModelId,
                    stream = true,
                    wireFormat = wireFormat,
                    httpStatusCode = statusCode,
                    headerModelId = headerModelId,
                )

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    val mapped = mapHttpStatus(statusCode, errorBody, trimmedKey)
                    if (isNonRetryableAuthOrRateError(statusCode)) {
                        throw CoarenaApiException(mapped)
                    }
                    streamingFailure = CoarenaApiException(mapped)
                    shouldFallbackToNonStreaming = true
                    return@use
                }

                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val body = response.body
                    ?: throw CoarenaApiException(AppError.SerializationError("Empty response body"))

                if (contentType.contains("application/json")) {
                    // Server ignored stream=true and returned a single JSON completion
                    val raw = body.string()
                    val completed = parser.parseChatCompletionJson(raw)
                    val resolvedCompleted = if (completed.responseModelId.isNullOrBlank() && headerModelId != null) {
                        completed.copy(responseModelId = headerModelId)
                    } else {
                        completed
                    }
                    _lastRequestDiagnostic.value = _lastRequestDiagnostic.value?.copy(
                        responseModelId = resolvedCompleted.responseModelId,
                    )
                    emit(resolvedCompleted)
                    return@flow
                }

                // Read SSE stream line by line
                var lastResponseModelId: String? = headerModelId
                val source = body.source()
                while (currentCoroutineContext().isActive) {
                    val line = source.readUtf8Line() ?: break
                    val trimmedLine = line.trim()
                    if (trimmedLine.isEmpty() || trimmedLine.startsWith(":")) {
                        continue
                    }
                    if (trimmedLine.startsWith("data:")) {
                        val payload = trimmedLine.removePrefix("data:").trim()
                        when (val parsed = parser.parseSseDataPayload(payload)) {
                            is SseParseResult.Chunk -> {
                                emittedAnyDelta = true
                                if (parsed.totalTokens != null) {
                                    lastTotalTokens = parsed.totalTokens
                                }
                                if (!parsed.responseModelId.isNullOrBlank()) {
                                    lastResponseModelId = parsed.responseModelId
                                }
                                emit(ChatStreamEvent.Delta(parsed.text, parsed.responseModelId))
                            }
                            is SseParseResult.UsageOnly -> {
                                lastTotalTokens = parsed.totalTokens
                                if (!parsed.responseModelId.isNullOrBlank()) {
                                    lastResponseModelId = parsed.responseModelId
                                }
                            }
                            is SseParseResult.ModelMetadata -> {
                                lastResponseModelId = parsed.responseModelId
                            }
                            SseParseResult.Done -> {
                                break
                            }
                            SseParseResult.Ignore -> Unit
                        }
                    } else if (trimmedLine.startsWith("{") && !emittedAnyDelta) {
                        // Some OpenAI-compatible proxies return plain JSON without Content-Type: application/json
                        val fullBuffer = trimmedLine + (source.readUtf8())
                        val completed = parser.parseChatCompletionJson(fullBuffer)
                        val resolvedCompleted = if (completed.responseModelId.isNullOrBlank() && headerModelId != null) {
                            completed.copy(responseModelId = headerModelId)
                        } else {
                            completed
                        }
                        _lastRequestDiagnostic.value = _lastRequestDiagnostic.value?.copy(
                            responseModelId = resolvedCompleted.responseModelId,
                        )
                        emit(resolvedCompleted)
                        return@flow
                    }
                }

                _lastRequestDiagnostic.value = _lastRequestDiagnostic.value?.copy(
                    responseModelId = lastResponseModelId,
                )

                if (emittedAnyDelta) {
                    emit(
                        ChatStreamEvent.Completed(
                            totalTokens = lastTotalTokens,
                            responseModelId = lastResponseModelId,
                        ),
                    )
                    return@flow
                } else {
                    shouldFallbackToNonStreaming = true
                }
            }
        } catch (e: CoarenaApiException) {
            if (e.appError is AppError.Unauthorized ||
                e.appError is AppError.Forbidden ||
                e.appError is AppError.RateLimited ||
                e.appError is AppError.MissingApiKey ||
                e.appError is AppError.InvalidUrl ||
                emittedAnyDelta
            ) {
                throw e
            }
            streamingFailure = e
            shouldFallbackToNonStreaming = true
        } catch (e: Exception) {
            if (emittedAnyDelta) {
                throw mapException(e, trimmedKey)
            }
            streamingFailure = mapException(e, trimmedKey)
            shouldFallbackToNonStreaming = true
        }

        if (shouldFallbackToNonStreaming) {
            val fallbackResult = executeNonStreamingJson(
                endpoint = endpoint,
                apiKey = trimmedKey,
                requestBodyJson = encodePayload(stream = false),
                sentModelId = trimmedModelId,
                wireFormat = wireFormat,
                requestHeaders = requestHeaders,
            )
            fallbackResult.fold(
                onSuccess = { completed -> emit(completed) },
                onFailure = { err ->
                    throw (err as? CoarenaApiException) ?: streamingFailure ?: mapException(err, trimmedKey)
                },
            )
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun executeNonStreamingJson(
        endpoint: String,
        apiKey: String,
        requestBodyJson: String,
        sentModelId: String,
        wireFormat: ApiWireFormat,
        requestHeaders: Map<String, String> = emptyMap(),
    ): Result<ChatStreamEvent.Completed> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .apply { requestHeaders.forEach { (name, value) -> header(name, value) } }
                .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val statusCode = response.code
                val headerModelId = response.header("x-model")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: response.header("x-coarena-model")?.trim()?.takeIf { it.isNotEmpty() }

                _lastRequestDiagnostic.value = RequestDiagnostic(
                    endpointUrl = endpoint,
                    sentModelId = sentModelId,
                    stream = false,
                    wireFormat = wireFormat,
                    httpStatusCode = statusCode,
                    headerModelId = headerModelId,
                )

                if (!response.isSuccessful) {
                    val errBody = response.body?.string()
                    return@withContext Result.failure(
                        CoarenaApiException(mapHttpStatus(statusCode, errBody, apiKey)),
                    )
                }

                val rawBody = response.body?.string().orEmpty()
                val completed = parser.parseChatCompletionJson(rawBody)
                val resolvedCompleted = if (completed.responseModelId.isNullOrBlank() && headerModelId != null) {
                    completed.copy(responseModelId = headerModelId)
                } else {
                    completed
                }
                _lastRequestDiagnostic.value = _lastRequestDiagnostic.value?.copy(
                    responseModelId = resolvedCompleted.responseModelId,
                )
                Result.success(resolvedCompleted)
            }
        } catch (e: Exception) {
            Result.failure(mapException(e, apiKey))
        }
    }

    private suspend fun <T> executeGet(
        baseUrl: String,
        endpointPath: String,
        apiKey: String,
        transform: (String) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return@withContext Result.failure(CoarenaApiException(AppError.MissingApiKey()))
        }
        val endpoint = CoarenaUrlBuilder.requireValidEndpoint(baseUrl, endpointPath)
            .getOrElse { return@withContext Result.failure(it) }

        try {
            val response = retrofitService.getEndpoint(
                url = endpoint,
                authorization = "Bearer $trimmedKey",
            )
            parseRetrofitResponse(response, trimmedKey, transform)
        } catch (e: Exception) {
            Result.failure(mapException(e, trimmedKey))
        }
    }

    private fun <T> parseRetrofitResponse(
        response: Response<ResponseBody>,
        apiKey: String,
        transform: (String) -> T,
    ): Result<T> {
        if (!response.isSuccessful) {
            val errBody = response.errorBody()?.string()
            return Result.failure(
                CoarenaApiException(mapHttpStatus(response.code(), errBody, apiKey)),
            )
        }
        val rawBody = response.body()?.string().orEmpty()
        return try {
            Result.success(transform(rawBody))
        } catch (e: Exception) {
            val reason = SecretRedactor.redact(e.message ?: e::class.simpleName, apiKey)
            Result.failure(CoarenaApiException(AppError.SerializationError(reason), e))
        }
    }

    private fun isNonRetryableAuthOrRateError(code: Int): Boolean {
        return code == 401 || code == 403 || code == 429
    }

    private fun mapHttpStatus(statusCode: Int, rawBody: String?, apiKey: String): AppError {
        val parsedDetails = parser.parseErrorMessage(rawBody, apiKey)
        val technical = if (parsedDetails.isNotBlank()) {
            "HTTP $statusCode: $parsedDetails"
        } else {
            "HTTP $statusCode"
        }
        return when (statusCode) {
            401 -> AppError.Unauthorized(technical)
            403 -> AppError.Forbidden(technical)
            429 -> AppError.RateLimited(technical)
            in 500..599 -> AppError.ServerError(statusCode, technical)
            else -> AppError.HttpError(statusCode, technical)
        }
    }

    private fun mapException(throwable: Throwable, apiKey: String): CoarenaApiException {
        if (throwable is CoarenaApiException) return throwable
        val safeMessage = SecretRedactor.redact(
            throwable.message ?: throwable::class.simpleName ?: "Unknown error",
            apiKey,
        )
        val appError = when (throwable) {
            is SocketTimeoutException -> AppError.Timeout(safeMessage)
            is UnknownHostException, is IOException -> AppError.NetworkError(safeMessage)
            is kotlinx.serialization.SerializationException -> AppError.SerializationError(safeMessage)
            else -> AppError.Unknown(safeMessage)
        }
        return CoarenaApiException(appError, throwable)
    }
}
