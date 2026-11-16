package com.example.data.remote

import com.example.domain.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoarenaUrlBuilderTest {

    @Test
    fun `builds all standard endpoints from default base url`() {
        val base = AppSettings.DEFAULT_BASE_URL
        assertEquals("https://coarena.ai/api/v1/models", CoarenaUrlBuilder.modelsUrl(base))
        assertEquals("https://coarena.ai/api/v1/chat/completions", CoarenaUrlBuilder.chatCompletionsUrl(base))
        assertEquals("https://coarena.ai/api/v1/credits", CoarenaUrlBuilder.creditsUrl(base))
        assertEquals("https://coarena.ai/api/v1/limits", CoarenaUrlBuilder.limitsUrl(base))
        assertEquals("https://coarena.ai/api/v1/requests", CoarenaUrlBuilder.requestsUrl(base))
    }

    @Test
    fun `normalizes trailing slashes and whitespace in custom base url`() {
        val custom = "  https://proxy.example.com/custom/v1///  "
        assertEquals("https://proxy.example.com/custom/v1", CoarenaUrlBuilder.normalizeBaseUrl(custom))
        assertEquals(
            "https://proxy.example.com/custom/v1/chat/completions",
            CoarenaUrlBuilder.chatCompletionsUrl(custom),
        )
    }

    @Test
    fun `rejects invalid schemes and malformed urls`() {
        assertFalse(CoarenaUrlBuilder.isValidBaseUrl("ftp://coarena.ai/api/v1"))
        assertFalse(CoarenaUrlBuilder.isValidBaseUrl("not-a-url"))
        assertFalse(CoarenaUrlBuilder.isValidBaseUrl(""))
        assertTrue(CoarenaUrlBuilder.isValidBaseUrl("http://localhost:8080/v1"))
    }
}
