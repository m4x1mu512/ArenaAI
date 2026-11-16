package com.example.data.remote

import com.example.data.security.SecretRedactor
import com.example.domain.model.AiModel
import com.example.domain.model.AppError
import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatStreamEvent
import com.example.domain.model.CreditsInfo
import com.example.domain.model.LimitsInfo
import com.example.domain.model.MessageRole
import com.example.domain.model.RequestHistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
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

    /**
     * Fetches available models from `{baseUrl}/models`.
     */
    suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<AiModel>> {
        return executeGet(baseUrl, "models", apiKey) { body ->
            parser.parseModels(body)
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
     * Sends chat messages to `{baseUrl}/chat/completions` with `stream: true`.
     * Automatically falls back to `stream: false` if streaming fails before emitting tokens
     * or if the endpoint returns standard JSON.
     */
    fun streamChatCompletion(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        history: List<ChatMessage>,
        systemPrompt: String,
    ): Flow<ChatStreamEvent> = flow {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            throw CoarenaApiException(AppError.MissingApiKey())
        }
        val endpoint = CoarenaUrlBuilder.requireValidEndpoint(baseUrl, "chat/completions")
            .getOrThrow()

        val dtoMessages = buildList {
            if (systemPrompt.isNotBlank()) {
                add(ChatMessageDto(role = MessageRole.SYSTEM.apiValue, content = systemPrompt))
            }
            history.filter { !it.isError && it.content.isNotBlank() }.forEach { msg ->
                add(ChatMessageDto(role = msg.role.apiValue, content = msg.content))
            }
        }

        val streamingRequestDto = ChatCompletionRequestDto(
            model = modelId,
            messages = dtoMessages,
            stream = true,
        )

        var emittedAnyDelta = false
        var lastTotalTokens: Int? = null
        var shouldFallbackToNonStreaming = false
        var streamingFailure: CoarenaApiException? = null

        try {
            val requestBodyJson = json.encodeToString(streamingRequestDto)
            val okRequest = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $trimmedKey")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(okRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    val mapped = mapHttpStatus(response.code, errorBody, trimmedKey)
                    if (isNonRetryableAuthOrRateError(response.code)) {
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
                    emit(completed)
                    return@flow
                }

                // Read SSE stream line by line
                var lastResponseModelId: String? = null
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
                        emit(completed)
                        return@flow
                    }
                }

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
            val fallbackResult = executeNonStreamingChat(
                endpoint = endpoint,
                apiKey = trimmedKey,
                requestDto = streamingRequestDto.copy(stream = false),
            )
            fallbackResult.fold(
                onSuccess = { completed -> emit(completed) },
                onFailure = { err ->
                    throw (err as? CoarenaApiException) ?: streamingFailure ?: mapException(err, trimmedKey)
                },
            )
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun executeNonStreamingChat(
        endpoint: String,
        apiKey: String,
        requestDto: ChatCompletionRequestDto,
    ): Result<ChatStreamEvent.Completed> = withContext(Dispatchers.IO) {
        try {
            val response = retrofitService.postChatCompletion(
                url = endpoint,
                authorization = "Bearer $apiKey",
                request = requestDto,
            )
            parseRetrofitResponse(response, apiKey) { rawBody ->
                parser.parseChatCompletionJson(rawBody)
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
