package com.example.data.remote

import com.example.data.security.SecretRedactor
import com.example.domain.model.AiModel
import com.example.domain.model.ChatStreamEvent
import com.example.domain.model.CreditsInfo
import com.example.domain.model.LimitItem
import com.example.domain.model.LimitsInfo
import com.example.domain.model.RequestHistoryItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Outcome of parsing a single SSE `data:` payload.
 */
sealed interface SseParseResult {
    data class Chunk(
        val text: String,
        val totalTokens: Int? = null,
        val responseModelId: String? = null,
    ) : SseParseResult
    data class UsageOnly(
        val totalTokens: Int,
        val responseModelId: String? = null,
    ) : SseParseResult
    data class ModelMetadata(val responseModelId: String) : SseParseResult
    data object Done : SseParseResult
    data object Ignore : SseParseResult
}

/**
 * Resilient JSON and SSE parser for Coarena and OpenAI-compatible payloads.
 * Handles both standard OpenAI envelopes and flexible Coarena field variations.
 */
class CoarenaApiParser(
    private val json: Json = DefaultJson,
) {

    /**
     * Parses `GET /models` response into a sorted list of [AiModel].
     */
    fun parseModels(rawJson: String): List<AiModel> {
        val root = json.parseToJsonElement(rawJson)
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                root["data"] as? JsonArray
                    ?: root["models"] as? JsonArray
                    ?: root["items"] as? JsonArray
                    ?: emptyList()
            }
            else -> emptyList()
        }

        return array.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }?.let { id ->
                    AiModel(id = id, displayName = id)
                }
                is JsonObject -> {
                    val rawId = element.stringValue("id")?.trim()
                    val fallbackModel = element.stringValue("model_id", "api_name", "slug", "model")?.trim()
                    val id = when {
                        rawId != null && (rawId.all { it.isDigit() } || isUuid(rawId)) && fallbackModel != null -> fallbackModel
                        rawId != null -> rawId
                        fallbackModel != null -> fallbackModel
                        else -> element.stringValue("name")?.trim()
                    } ?: return@mapNotNull null

                    val displayName = element.stringValue("display_name", "name", "title")?.trim() ?: id
                    val ownedBy = element.stringValue("owned_by", "provider", "owner")
                    val contextLength = element.intValue("context_length", "max_context_tokens", "context_window")
                    AiModel(
                        id = id,
                        displayName = displayName,
                        ownedBy = ownedBy,
                        contextLength = contextLength,
                    )
                }
                else -> null
            }
        }.distinctBy { it.id }
    }

    /**
     * Parses a non-streaming `POST /chat/completions` or `POST /responses` JSON response.
     */
    fun parseChatCompletionJson(rawJson: String): ChatStreamEvent.Completed {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val choices = root["choices"] as? JsonArray
        val firstChoice = choices?.firstOrNull() as? JsonObject

        val content = extractChoiceText(firstChoice)
            ?: extractResponsesOutputText(root["output"] as? JsonArray)
            ?: root.stringValue("output_text", "text", "content", "response")
            ?: throw IllegalStateException("Missing assistant content in response")

        val nestedResponse = root["response"] as? JsonObject
        val usageObj = (root["usage"] as? JsonObject) ?: (nestedResponse?.get("usage") as? JsonObject)
        val totalTokens = usageObj?.intValue("total_tokens")
            ?: run {
                val prompt = usageObj?.intValue("prompt_tokens", "input_tokens")
                val completion = usageObj?.intValue("completion_tokens", "output_tokens")
                if (prompt != null && completion != null) prompt + completion else null
            }

        val responseModelId = root.stringValue("model")
            ?: nestedResponse?.stringValue("model")

        return ChatStreamEvent.Completed(
            fullText = content,
            totalTokens = totalTokens,
            responseModelId = responseModelId,
        )
    }

    /**
     * Parses a single SSE `data:` payload line (without the `data:` prefix).
     */
    fun parseSseDataPayload(payload: String): SseParseResult {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return SseParseResult.Ignore
        if (trimmed == "[DONE]") return SseParseResult.Done

        val root = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            ?: return SseParseResult.Ignore

        // Check for inline SSE error object
        val errorObj = root["error"] as? JsonObject
        if (errorObj != null) {
            val message = errorObj.stringValue("message", "detail") ?: "Stream error"
            throw IllegalStateException(message)
        }

        val nestedResponse = root["response"] as? JsonObject
        val responseModelId = root.stringValue("model")
            ?: nestedResponse?.stringValue("model")
        val usageObj = (root["usage"] as? JsonObject) ?: (nestedResponse?.get("usage") as? JsonObject)
        val totalTokens = usageObj?.intValue("total_tokens")
            ?: run {
                val input = usageObj?.intValue("prompt_tokens", "input_tokens")
                val output = usageObj?.intValue("completion_tokens", "output_tokens")
                if (input != null && output != null) input + output else null
            }

        val eventType = root.stringValue("type")
        if (eventType == "response.output_text.delta") {
            val delta = root.stringValue("delta")
            if (!delta.isNullOrEmpty()) {
                return SseParseResult.Chunk(
                    text = delta,
                    totalTokens = totalTokens,
                    responseModelId = responseModelId,
                )
            }
        }

        val choices = root["choices"] as? JsonArray
        val firstChoice = choices?.firstOrNull() as? JsonObject
        val deltaText = extractDeltaText(firstChoice) ?: root.stringValue("delta", "text")

        return when {
            !deltaText.isNullOrEmpty() -> SseParseResult.Chunk(
                text = deltaText,
                totalTokens = totalTokens,
                responseModelId = responseModelId,
            )
            totalTokens != null -> SseParseResult.UsageOnly(
                totalTokens = totalTokens,
                responseModelId = responseModelId,
            )
            !responseModelId.isNullOrBlank() -> SseParseResult.ModelMetadata(responseModelId)
            else -> SseParseResult.Ignore
        }
    }

    private fun extractResponsesOutputText(output: JsonArray?): String? {
        if (output == null) return null
        val collected = buildString {
            for (item in output) {
                val obj = item as? JsonObject ?: continue
                val contentArray = obj["content"] as? JsonArray ?: continue
                for (part in contentArray) {
                    val partObj = part as? JsonObject ?: continue
                    val text = partObj.stringValue("text", "output_text", "content")
                    if (!text.isNullOrEmpty()) {
                        append(text)
                    }
                }
            }
        }
        return collected.takeIf { it.isNotEmpty() }
    }

    /**
     * Parses `GET /credits` into [CreditsInfo].
     */
    fun parseCredits(rawJson: String): CreditsInfo {
        val root = unwrapDataObject(json.parseToJsonElement(rawJson))
        val balance = root.scalarString(
            "balance",
            "credits",
            "available_credits",
            "remaining_credits",
            "remaining",
            "total_available",
            "amount",
        ) ?: "—"
        val currency = root.stringValue("currency", "unit")
        val totalGranted = root.scalarString("total_granted", "granted", "limit")
        val totalUsed = root.scalarString("total_used", "used", "usage")

        val handledKeys = setOf(
            "balance", "credits", "available_credits", "remaining_credits",
            "remaining", "total_available", "amount", "currency", "unit",
            "total_granted", "granted", "limit", "total_used", "used", "usage",
        )
        val extras = root.entries
            .filter { (key, value) -> key !in handledKeys && value is JsonPrimitive }
            .associate { (key, value) -> key to (value.jsonPrimitive.contentOrNull ?: "") }
            .filterValues { it.isNotBlank() }

        return CreditsInfo(
            balance = balance,
            currency = currency,
            totalGranted = totalGranted,
            totalUsed = totalUsed,
            extraDetails = extras,
        )
    }

    /**
     * Parses `GET /limits` into [LimitsInfo].
     */
    fun parseLimits(rawJson: String): LimitsInfo {
        val element = json.parseToJsonElement(rawJson)
        val root = unwrapDataObject(element)
        val rpm = root.scalarString("requests_per_minute", "rpm", "rate_limit_requests")
        val tpm = root.scalarString("tokens_per_minute", "tpm", "rate_limit_tokens")
        val daily = root.scalarString("daily_limit", "requests_per_day", "rpd", "daily_quota")
        val remaining = root.scalarString("remaining_requests", "remaining", "requests_remaining")

        val items = mutableListOf<LimitItem>()
        collectFlatItems(root, prefix = "", out = items)

        return LimitsInfo(
            requestsPerMinute = rpm,
            tokensPerMinute = tpm,
            dailyLimit = daily,
            remainingRequests = remaining,
            items = items,
        )
    }

    /**
     * Parses `GET /requests` into a list of [RequestHistoryItem].
     */
    fun parseRequests(rawJson: String): List<RequestHistoryItem> {
        val root = json.parseToJsonElement(rawJson)
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                root["data"] as? JsonArray
                    ?: root["requests"] as? JsonArray
                    ?: root["items"] as? JsonArray
                    ?: root["history"] as? JsonArray
                    ?: emptyList()
            }
            else -> emptyList()
        }

        return array.mapIndexedNotNull { index, item ->
            val obj = item as? JsonObject ?: return@mapIndexedNotNull null
            val id = obj.scalarString("id", "request_id") ?: "req_$index"
            val createdAt = obj.scalarString("created_at", "timestamp", "date", "created") ?: "—"
            val model = obj.scalarString("model", "model_id") ?: "—"
            val status = obj.scalarString("status", "state")
                ?: obj.intValue("status_code", "http_status")?.toString()
                ?: "ok"

            val usageObj = obj["usage"] as? JsonObject
            val promptTokens = obj.intValue("prompt_tokens", "input_tokens")
                ?: usageObj?.intValue("prompt_tokens", "input_tokens")
            val completionTokens = obj.intValue("completion_tokens", "output_tokens")
                ?: usageObj?.intValue("completion_tokens", "output_tokens")
            val totalTokens = obj.intValue("total_tokens", "tokens")
                ?: usageObj?.intValue("total_tokens", "tokens")
                ?: if (promptTokens != null && completionTokens != null) {
                    promptTokens + completionTokens
                } else {
                    null
                }

            RequestHistoryItem(
                id = id,
                createdAt = createdAt,
                model = model,
                status = status,
                totalTokens = totalTokens,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
            )
        }
    }

    /**
     * Extracts a readable error description from an HTTP error body while redacting secrets.
     */
    fun parseErrorMessage(rawBody: String?, apiKey: String? = null): String {
        if (rawBody.isNullOrBlank()) return ""
        val extracted = runCatching {
            val element = json.parseToJsonElement(rawBody)
            if (element is JsonObject) {
                val nestedError = element["error"] as? JsonObject
                nestedError?.stringValue("message", "detail", "code")
                    ?: element.stringValue("message", "detail", "error")
                    ?: rawBody
            } else {
                rawBody
            }
        }.getOrElse { rawBody }

        return SecretRedactor.redact(extracted, apiKey).take(500)
    }

    private fun extractChoiceText(choice: JsonObject?): String? {
        if (choice == null) return null
        val message = choice["message"] as? JsonObject
        val delta = choice["delta"] as? JsonObject
        return extractContentString(message?.get("content"))
            ?: extractContentString(delta?.get("content"))
            ?: choice.stringValue("text")
    }

    private fun extractDeltaText(choice: JsonObject?): String? {
        if (choice == null) return null
        val delta = choice["delta"] as? JsonObject
        val message = choice["message"] as? JsonObject
        return extractContentString(delta?.get("content"))
            ?: extractContentString(message?.get("content"))
            ?: choice.stringValue("text")
    }

    private fun extractContentString(element: JsonElement?): String? {
        return when (element) {
            is JsonPrimitive -> element.contentOrNull
            is JsonArray -> element.mapNotNull { part ->
                (part as? JsonObject)?.stringValue("text", "content")
                    ?: (part as? JsonPrimitive)?.contentOrNull
            }.joinToString("").takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    private fun unwrapDataObject(element: JsonElement): JsonObject {
        val obj = element as? JsonObject ?: return JsonObject(emptyMap())
        val innerData = obj["data"] as? JsonObject
        val innerLimits = obj["limits"] as? JsonObject
        val innerCredits = obj["credits"] as? JsonObject
        return innerData ?: innerLimits ?: innerCredits ?: obj
    }

    private fun collectFlatItems(obj: JsonObject, prefix: String, out: MutableList<LimitItem>) {
        for ((key, value) in obj) {
            val fullKey = if (prefix.isEmpty()) key else "$prefix.$key"
            when (value) {
                is JsonPrimitive -> {
                    val str = value.contentOrNull
                    if (!str.isNullOrBlank()) {
                        out.add(LimitItem(key = fullKey, value = str))
                    }
                }
                is JsonObject -> collectFlatItems(value, fullKey, out)
                is JsonArray -> {
                    if (value.isNotEmpty()) {
                        out.add(LimitItem(key = fullKey, value = "${value.size} entries"))
                    }
                }
            }
        }
    }

    private fun JsonObject.stringValue(vararg keys: String): String? {
        for (key in keys) {
            val prim = this[key] as? JsonPrimitive ?: continue
            val value = prim.contentOrNull
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun JsonObject.scalarString(vararg keys: String): String? {
        for (key in keys) {
            val prim = this[key] as? JsonPrimitive ?: continue
            val value = prim.contentOrNull
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun JsonObject.intValue(vararg keys: String): Int? {
        for (key in keys) {
            val prim = this[key] as? JsonPrimitive ?: continue
            prim.intOrNull?.let { return it }
            prim.contentOrNull?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun isUuid(value: String): Boolean {
        return value.length == 36 && value.count { it == '-' } == 4
    }

    companion object {
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}
