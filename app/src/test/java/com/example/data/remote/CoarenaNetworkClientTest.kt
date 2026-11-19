package com.example.data.remote

import com.example.domain.model.ApiWireFormat
import com.example.domain.model.AppError
import com.example.domain.model.ChatAttachment
import com.example.domain.model.ChatAttachmentKind
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
import java.io.File
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
    fun `captures x-model response header when JSON omits model field`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("x-model", "gpt-6-luna")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"Done"}}]}"""),
        )

        val events = client.streamChatCompletion(
            baseUrl = baseUrl,
            apiKey = "sk-test-1234567890",
            modelId = "deepseek-v4-1-flash-1017",
            history = listOf(ChatMessage(id = "1", role = MessageRole.USER, content = "Hi")),
            systemPrompt = "",
        ).toList()

        assertEquals(1, events.size)
        val completed = events[0] as ChatStreamEvent.Completed
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

    @Test
    fun `uses responses endpoint and records lastRequestDiagnostic when wireFormat is RESPONSES`() = runTest {
        val sseBody = """
            data: {"type":"response.output_text.delta","delta":"DeepSeek OK","response":{"model":"deepseek-v4-1-flash-1017"}}

            data: {"type":"response.completed","response":{"model":"deepseek-v4-1-flash-1017","usage":{"input_tokens":5,"output_tokens":3}}}

        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody),
        )

        val secretKey = "sk-secret-responses-key"
        val events = client.streamChatCompletion(
            baseUrl = baseUrl,
            apiKey = secretKey,
            modelId = "deepseek-v4-1-flash-1017",
            history = listOf(ChatMessage(id = "1", role = MessageRole.USER, content = "Hi")),
            systemPrompt = "Be concise.",
            useStreaming = true,
            wireFormat = ApiWireFormat.RESPONSES,
        ).toList()

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.endsWith("/api/v1/responses"))
        val sentBody = recorded.body.readUtf8()
        assertTrue(sentBody.contains(""""model":"deepseek-v4-1-flash-1017""""))
        assertTrue(sentBody.contains(""""input":"""))
        assertTrue(sentBody.contains(""""instructions":"Be concise.""""))

        assertEquals(2, events.size)
        assertEquals("DeepSeek OK", (events[0] as ChatStreamEvent.Delta).textChunk)
        val completed = events[1] as ChatStreamEvent.Completed
        assertEquals("deepseek-v4-1-flash-1017", completed.responseModelId)
        assertEquals(8, completed.totalTokens)

        val diag = client.lastRequestDiagnostic.value!!
        assertEquals(ApiWireFormat.RESPONSES, diag.wireFormat)
        assertEquals("deepseek-v4-1-flash-1017", diag.sentModelId)
        assertEquals("deepseek-v4-1-flash-1017", diag.responseModelId)
        assertEquals(200, diag.httpStatusCode)
        assertFalse(diag.toString().contains(secretKey))
    }

    @Test
    fun `sends stream false directly when useStreaming is disabled`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"model":"deepseek-v4-1-flash-1017","choices":[{"message":{"role":"assistant","content":"Direct JSON"}}]}"""),
        )

        val events = client.streamChatCompletion(
            baseUrl = baseUrl,
            apiKey = "sk-test-non-stream",
            modelId = "deepseek-v4-1-flash-1017",
            history = listOf(ChatMessage(id = "1", role = MessageRole.USER, content = "Ping")),
            systemPrompt = "",
            useStreaming = false,
            wireFormat = ApiWireFormat.CHAT_COMPLETIONS,
        ).toList()

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.endsWith("/api/v1/chat/completions"))
        val sentBody = recorded.body.readUtf8()
        assertTrue(sentBody.contains(""""stream":false"""))
        assertTrue(sentBody.contains(""""model":"deepseek-v4-1-flash-1017""""))

        assertEquals(1, events.size)
        val completed = events[0] as ChatStreamEvent.Completed
        assertEquals("Direct JSON", completed.fullText)
        assertEquals("deepseek-v4-1-flash-1017", completed.responseModelId)

        val diag = client.lastRequestDiagnostic.value!!
        assertFalse(diag.stream)
        assertEquals(200, diag.httpStatusCode)
    }
    @Test
    fun `sends image attachments as OpenAI compatible image url parts`() = runTest {
        val imageFile = File.createTempFile("arena-image", ".jpg")
        imageFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"choices":[{"message":{"role":"assistant","content":"I can see it"}}]}"""),
            )

            client.streamChatCompletion(
                baseUrl = baseUrl,
                apiKey = "sk-test-image-key",
                modelId = "gpt-6-luna",
                history = listOf(
                    ChatMessage(
                        id = "image-message",
                        role = MessageRole.USER,
                        content = "What is in this image?",
                        attachments = listOf(
                            ChatAttachment(
                                id = "image-1",
                                localPath = imageFile.absolutePath,
                                mimeType = "image/jpeg",
                                sizeBytes = imageFile.length(),
                                kind = ChatAttachmentKind.USER_IMAGE,
                            ),
                        ),
                    ),
                ),
                systemPrompt = "",
                useStreaming = false,
            ).toList()

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains(""""type":"image_url""""))
            assertTrue(body.contains(""""url":"data:image/jpeg;base64,"""))
            assertTrue(body.contains(""""text":"What is in this image?""""))
        } finally {
            imageFile.delete()
        }
    }


    @Test
    fun `adds Cloudflare AI Gateway ID header to streamed requests`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"),
        )
        client.streamChatCompletion(
            baseUrl = baseUrl,
            apiKey = "cf-test-token",
            modelId = "@cf/meta/llama-test",
            history = listOf(ChatMessage(id = "cf-1", role = MessageRole.USER, content = "Hi")),
            systemPrompt = "",
            requestHeaders = mapOf("cf-aig-gateway-id" to "default"),
        ).toList()
        assertEquals("default", server.takeRequest().getHeader("cf-aig-gateway-id"))
    }

    @Test
    fun `generates image through Cloudflare FLUX native run endpoint`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"success":true,"result":{"image":"aGVsbG8="}}"""),
        )

        val accountId = "0123456789abcdef0123456789abcdef"
        val result = client.generateCloudflareImage(
            accountId = accountId,
            apiKey = "cf-test-token",
            prompt = "A lighthouse above the sea at sunset",
            apiBaseUrl = server.url("/client/v4").toString().trimEnd('/'),
        )

        assertTrue(result.isSuccess)
        assertEquals("aGVsbG8=", result.getOrThrow().base64Data)
        assertEquals("image/jpeg", result.getOrThrow().mimeType)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(
            "/client/v4/accounts/$accountId/ai/run/@cf/black-forest-labs/flux-1-schnell",
            recorded.path,
        )
        assertEquals("Bearer cf-test-token", recorded.getHeader("Authorization"))
        val requestBody = recorded.body.readUtf8()
        assertTrue(requestBody.contains(""""prompt":"A lighthouse above the sea at sunset""""))
        assertTrue(requestBody.contains(""""steps":4"""))
    }

}
