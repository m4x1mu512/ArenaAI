package com.example.data.remote

import com.example.domain.model.ChatMessage
import com.example.domain.model.MessageRole
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnthropicNetworkClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AnthropicNetworkClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AnthropicNetworkClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fetchModels_usesAnthropicHeadersAndParsesModelList() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"data":[{"id":"claude-sonnet-test","display_name":"Claude Sonnet Test"}]}""",
            ),
        )

        val result = client.fetchModels(baseUrl(), "secret-key")

        assertTrue(result.isSuccess)
        assertEquals("claude-sonnet-test", result.getOrThrow().single().id)
        val request = server.takeRequest()
        assertEquals("/v1/models?limit=1000", request.path)
        assertEquals("secret-key", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
    }

    @Test
    fun streamChat_parsesTextDeltaAndCompletion() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    event: message_start
                    data: {"type":"message_start","message":{"model":"claude-sonnet-test","usage":{"input_tokens":3,"output_tokens":1}}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello"}}

                    event: message_delta
                    data: {"type":"message_delta","usage":{"output_tokens":4}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """.trimIndent(),
                ),
        )

        val events = client.streamChat(
            baseUrl = baseUrl(),
            apiKey = "secret-key",
            modelId = "claude-sonnet-test",
            history = listOf(ChatMessage(id = "u1", role = MessageRole.USER, content = "Hi")),
            systemPrompt = "Be concise",
            useStreaming = true,
        ).toList()

        assertEquals("Hello", (events.first() as com.example.domain.model.ChatStreamEvent.Delta).textChunk)
        val completed = events.last() as com.example.domain.model.ChatStreamEvent.Completed
        assertEquals("Hello", completed.fullText)
        assertEquals("claude-sonnet-test", completed.responseModelId)
        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        assertEquals("secret-key", request.getHeader("x-api-key"))
        assertTrue(request.body.readUtf8().contains("\"max_tokens\":4096"))
    }

    private fun baseUrl(): String = server.url("/v1").toString().trimEnd('/')
}
