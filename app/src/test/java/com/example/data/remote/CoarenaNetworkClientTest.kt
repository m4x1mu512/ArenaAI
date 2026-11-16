package com.example.data.remote

import com.example.domain.model.AppError
import com.example.domain.model.ChatMessage
import com.example.domain.model.ChatStreamEvent
import com.example.domain.model.MessageRole
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class CoarenaNetworkClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: CoarenaNetworkClient
    private lateinit var baseUrl: String

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/api/v1").toString()

        val json = CoarenaApiParser.DefaultJson
        val okHttp = OkHttpClient()
        val retrofitService = Retrofit.Builder()
            .baseUrl("https://coarena.ai/api/v1/")
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CoarenaRetrofitService::class.java)

        client = CoarenaNetworkClient(
            okHttpClient = okHttp,
            retrofitService = retrofitService,
            parser = CoarenaApiParser(json),
            json = json,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `streams SSE chunks and sends Authorization Bearer header`() = runTest {
        val sseBody = """
            data: {"choices":[{"delta":{"content":"Привет, "}}]}

            data: {"choices":[{"delta":{"content":"мир!"}}]}

            data: [DONE]

        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody),
        )

        val events = client.streamChatCompletion(
            baseUrl = baseUrl,
            apiKey = "sk-test-1234567890",
            modelId = "gpt-4o",
            history = listOf(ChatMessage(id = "1", role = MessageRole.USER, content = "Hi")),
            systemPrompt = "You are a helpful assistant.",
        ).toList()

        val recorded = server.takeRequest()
        assertEquals("Bearer sk-test-1234567890", recorded.getHeader("Authorization"))
        val sentBody = recorded.body.readUtf8()
        assertTrue(sentBody.contains(""""stream":true"""))
        assertTrue(sentBody.contains(""""model":"gpt-4o""""))

        assertEquals(3, events.size)
        assertEquals("Привет, ", (events[0] as ChatStreamEvent.Delta).textChunk)
        assertEquals("мир!", (events[1] as ChatStreamEvent.Delta).textChunk)
        assertTrue(events[2] is ChatStreamEvent.Completed)
    }

    @Test
    fun `propagates responseModelId from SSE metadata and chunks`() = runTest {
        val sseBody = """
            data: {"model":"gpt-6-luna","choices":[{"delta":{"role":"assistant"}}]}

            data: {"choices":[{"delta":{"content":"Hi"}}]}

            data: [DONE]

        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody),
        )

        val events = client.streamChatCompletion(
            baseUrl = baseUrl,
            apiKey = "sk-test-1234567890",
            modelId = "deepseek-v4-1-flash-1017",
            history = listOf(ChatMessage(id = "1", role = MessageRole.USER, content = "Hi")),
            systemPrompt = "",
        ).toList()

        val recorded = server.takeRequest()
        assertTrue(recorded.body.readUtf8().contains(""""model":"deepseek-v4-1-flash-1017""""))

        assertEquals(2, events.size)
        assertEquals("Hi", (events[0] as ChatStreamEvent.Delta).textChunk)
        val completed = events[1] as ChatStreamEvent.Completed
        assertEquals("gpt-6-luna", completed.responseModelId)
    }

    @Test
    fun `retries without stream when SSE returns non-auth error`() = runTest {
        // First response: streaming rejected (HTTP 400)
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"message":"Streaming not supported"}}"""),
        )
        // Second response: non-streaming fallback succeeds
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"Fallback OK"}}],"usage":{"total_tokens":15}}"""),
        )

        val events = client.streamChatCompletion(
            baseUrl = baseUrl,
            apiKey = "sk-test-1234567890",
            modelId = "coarena-mini",
            history = listOf(ChatMessage(id = "1", role = MessageRole.USER, content = "Ping")),
            systemPrompt = "You are a helpful assistant.",
        ).toList()

        val firstReq = server.takeRequest()
        val secondReq = server.takeRequest()
        assertTrue(firstReq.body.readUtf8().contains(""""stream":true"""))
        assertTrue(secondReq.body.readUtf8().contains(""""stream":false"""))

        assertEquals(1, events.size)
        val completed = events[0] as ChatStreamEvent.Completed
        assertEquals("Fallback OK", completed.fullText)
        assertEquals(15, completed.totalTokens)
    }

    @Test
    fun `maps 401 unauthorized and redacts API key from technical details`() = runTest {
        val secretKey = "sk-my-very-secret-key-999999"
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Incorrect API key provided: $secretKey"}}"""),
        )

        val result = client.fetchModels(baseUrl, secretKey)
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull() as CoarenaApiException
        assertTrue(exception.appError is AppError.Unauthorized)
        assertFalse(exception.appError.technicalReason.contains(secretKey))
        assertTrue(exception.appError.technicalReason.contains("[REDACTED]"))
    }

    @Test
    fun `maps 429 rate limited and 503 server errors properly`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"message":"Too many requests"}"""))
        val rateLimited = client.fetchCredits(baseUrl, "sk-test-key-12345")
        assertTrue((rateLimited.exceptionOrNull() as CoarenaApiException).appError is AppError.RateLimited)

        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"message":"Unavailable"}"""))
        val serverErr = client.fetchLimits(baseUrl, "sk-test-key-12345")
        val err = (serverErr.exceptionOrNull() as CoarenaApiException).appError
        assertTrue(err is AppError.ServerError)
        assertEquals(503, (err as AppError.ServerError).statusCode)
    }
}
