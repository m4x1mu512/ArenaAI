package com.example.data.remote

import com.example.data.security.SecretRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoarenaApiParserTest {

    private val parser = CoarenaApiParser()

    @Test
    fun `parses OpenAI style models response`() {
        val json = """
            {
              "object": "list",
              "data": [
                {"id": "gpt-4o", "owned_by": "openai", "context_length": 128000},
                {"id": "claude-3.7-sonnet", "display_name": "Claude 3.7 Sonnet", "owned_by": "anthropic"}
              ]
            }
        """.trimIndent()

        val models = parser.parseModels(json)
        assertEquals(2, models.size)
        assertEquals("gpt-4o", models[0].id)
        assertEquals(128000, models[0].contextLength)
        assertEquals("Claude 3.7 Sonnet", models[1].displayName)
    }

    @Test
    fun `prefers slug, api_name, or model_id over record id`() {
        val json = """
            {
              "data": [
                {"id": 42, "slug": "deepseek-v4-1-flash-1017", "name": "DeepSeek v4.1 Flash"},
                {"id": "550e8400-e29b-41d4-a716-446655440000", "model_id": "gpt-6-luna", "display_name": "GPT-6 Luna"},
                {"id": "rec_999", "api_name": "deepseek-v4-1-flash-1017", "display_name": "DeepSeek API"}
              ]
            }
        """.trimIndent()

        val models = parser.parseModels(json)
        assertEquals(2, models.size)
        assertEquals("deepseek-v4-1-flash-1017", models[0].id)
        assertEquals("DeepSeek v4.1 Flash", models[0].displayName)
        assertEquals("gpt-6-luna", models[1].id)
        assertEquals("GPT-6 Luna", models[1].displayName)
    }

    @Test
    fun `parses SSE delta chunk, metadata, and DONE sentinel`() {
        val chunkPayload = """{"id":"1","model":"deepseek-v4-1-flash-1017","choices":[{"index":0,"delta":{"content":"Hello "}}]}"""
        val result = parser.parseSseDataPayload(chunkPayload)
        assertTrue(result is SseParseResult.Chunk)
        val chunk = result as SseParseResult.Chunk
        assertEquals("Hello ", chunk.text)
        assertEquals("deepseek-v4-1-flash-1017", chunk.responseModelId)

        val metaPayload = """{"id":"1","model":"gpt-6-luna","choices":[{"index":0,"delta":{"role":"assistant"}}]}"""
        val metaResult = parser.parseSseDataPayload(metaPayload)
        assertEquals(SseParseResult.ModelMetadata("gpt-6-luna"), metaResult)

        assertEquals(SseParseResult.Done, parser.parseSseDataPayload("[DONE]"))
    }

    @Test
    fun `parses non-streaming chat completion JSON with usage and model`() {
        val json = """
            {
              "id": "chatcmpl-123",
              "model": "deepseek-v4-1-flash-1017",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "Kotlin rocks!"}}
              ],
              "usage": {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20}
            }
        """.trimIndent()

        val completed = parser.parseChatCompletionJson(json)
        assertEquals("Kotlin rocks!", completed.fullText)
        assertEquals(20, completed.totalTokens)
        assertEquals("deepseek-v4-1-flash-1017", completed.responseModelId)
    }

    @Test
    fun `parses credits, limits, and requests resiliently`() {
        val creditsJson = """{"data":{"balance":"14.50","currency":"USD","total_used":"5.50"}}"""
        val credits = parser.parseCredits(creditsJson)
        assertEquals("14.50", credits.balance)
        assertEquals("USD", credits.currency)
        assertEquals("5.50", credits.totalUsed)

        val limitsJson = """{"requests_per_minute":60,"tokens_per_minute":90000,"remaining_requests":59}"""
        val limits = parser.parseLimits(limitsJson)
        assertEquals("60", limits.requestsPerMinute)
        assertEquals("90000", limits.tokensPerMinute)
        assertEquals("59", limits.remainingRequests)

        val requestsJson = """
            {
              "data": [
                {"id": "req_1", "created_at": "2026-11-16T10:00:00Z", "model": "gpt-4o", "status": "completed", "total_tokens": 312}
              ]
            }
        """.trimIndent()
        val requests = parser.parseRequests(requestsJson)
        assertEquals(1, requests.size)
        assertEquals("gpt-4o", requests[0].model)
        assertEquals("completed", requests[0].status)
        assertEquals(312, requests[0].totalTokens)
    }

    @Test
    fun `redacts API keys and bearer tokens from error messages`() {
        val secretKey = "sk-super-secret-token-123456789"
        val raw = """{"error":{"message":"Invalid key Bearer $secretKey provided"}}"""
        val parsed = parser.parseErrorMessage(raw, secretKey)
        assertFalse(parsed.contains(secretKey))
        assertTrue(parsed.contains("[REDACTED]"))

        val redacted = SecretRedactor.redact("Failed with key $secretKey", secretKey)
        assertFalse(redacted.contains(secretKey))
    }
}
