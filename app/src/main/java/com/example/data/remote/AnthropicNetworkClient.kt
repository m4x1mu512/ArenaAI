package com.example.data.remote

import com.example.domain.model.AiModel
import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatStreamEvent
import com.example.domain.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native Anthropic Messages API client. Anthropic uses x-api-key and its own
 * message/content-block schema; it is intentionally not sent through the OpenAI adapter.
 */
@Singleton
class AnthropicNetworkClient @Inject constructor(
    private val client: OkHttpClient,
) {
    suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<AiModel>> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(CoarenaUrlBuilder.buildEndpoint(baseUrl, "models") + "?limit=1000")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    check(response.isSuccessful) { "Anthropic HTTP ${response.code}: ${redact(body, apiKey).take(400)}" }
                    val root = Json.parseToJsonElement(body).jsonObject
                    root["data"]?.jsonArray.orEmpty().mapNotNull { item ->
                        val model = item.jsonObject
                        val id = model["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        AiModel(
                            id = id,
                            displayName = model["display_name"]?.jsonPrimitive?.contentOrNull ?: id,
                            ownedBy = "Anthropic",
                        )
                    }
                }
            }
        }

    fun streamChat(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        history: List<ChatMessage>,
        systemPrompt: String,
        useStreaming: Boolean,
    ): Flow<ChatStreamEvent> = flow {
        val messages = buildJsonArray {
            history.filter { it.role != MessageRole.SYSTEM }.forEach { message ->
                add(buildJsonObject {
                    put("role", if (message.role == MessageRole.ASSISTANT) "assistant" else "user")
                    put("content", buildContent(message))
                })
            }
        }
        val payload = buildJsonObject {
            put("model", modelId)
            put("max_tokens", 4096)
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            put("messages", messages)
            put("stream", useStreaming)
        }.toString()

        val request = Request.Builder()
            .url(CoarenaUrlBuilder.buildEndpoint(baseUrl, "messages"))
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body ?: error("Anthropic returned an empty response.")
            if (!response.isSuccessful) {
                val errorBody = responseBody.string()
                error("Anthropic HTTP ${response.code}: ${redact(errorBody, apiKey).take(500)}")
            }
            if (!useStreaming) {
                val root = Json.parseToJsonElement(responseBody.string()).jsonObject
                val text = root["content"]?.jsonArray.orEmpty().mapNotNull { block ->
                    block.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                }.joinToString("")
                val usage = root["usage"]?.jsonObject
                val tokens = (usage?.get("input_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0) +
                    (usage?.get("output_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0)
                val responseModel = root["model"]?.jsonPrimitive?.contentOrNull
                emit(ChatStreamEvent.Delta(text, responseModel))
                emit(ChatStreamEvent.Completed(text, tokens.takeIf { it > 0 }, responseModel))
            } else {
                val source = responseBody.source()
                var responseModel: String? = null
                var outputTokens: Int? = null
                var accumulated = StringBuilder()
                var completed = false
                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: continue
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val event = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    when (event["type"]?.jsonPrimitive?.contentOrNull) {
                        "message_start" -> {
                            val message = event["message"]?.jsonObject
                            responseModel = message?.get("model")?.jsonPrimitive?.contentOrNull
                        }
                        "content_block_delta" -> {
                            val delta = event["delta"]?.jsonObject
                            val chunk = delta?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (chunk.isNotEmpty()) {
                                accumulated.append(chunk)
                                emit(ChatStreamEvent.Delta(chunk, responseModel))
                            }
                        }
                        "message_delta" -> {
                            outputTokens = event["usage"]?.jsonObject
                                ?.get("output_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                        }
                        "message_stop" -> {
                            completed = true
                            emit(ChatStreamEvent.Completed(
                                fullText = accumulated.toString(),
                                totalTokens = outputTokens,
                                responseModelId = responseModel,
                            ))
                        }
                        "error" -> error("Anthropic stream returned an error.")
                    }
                }
                if (!completed && accumulated.isNotEmpty()) {
                    emit(ChatStreamEvent.Completed(
                        fullText = accumulated.toString(),
                        totalTokens = outputTokens,
                        responseModelId = responseModel,
                    ))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun redact(value: String, apiKey: String): String =
        if (apiKey.isBlank()) value else value.replace(apiKey, "[REDACTED]")

    private fun buildContent(message: ChatMessage): JsonArray = buildJsonArray {
        if (message.content.isNotBlank()) {
            add(buildJsonObject {
                put("type", "text")
                put("text", message.content)
            })
        }
        message.attachments.forEach { attachment ->
            val bytes = File(attachment.localPath).readBytes()
            require(bytes.size <= MAX_IMAGE_BYTES) { "Image is too large for Anthropic API." }
            add(buildJsonObject {
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", attachment.mimeType)
                    put("data", Base64.getEncoder().encodeToString(bytes))
                })
            })
        }
    }

    private companion object {
        const val API_VERSION = "2023-06-01"
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
