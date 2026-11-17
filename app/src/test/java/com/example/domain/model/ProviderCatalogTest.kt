package com.example.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {
    @Test
    fun catalogContainsCommonProvidersAndCustomEndpoint() {
        val ids = ProviderCatalog.presets.map { it.id }

        assertTrue(ids.containsAll(listOf(
            "coarena",
            "openai",
            "anthropic",
            "google-gemini",
            "deepseek",
            "openrouter",
            "groq",
            "mistral",
            "together",
            "xai",
            "cloudflare",
        )))
        assertEquals(ProviderProtocol.ANTHROPIC_MESSAGES, ProviderCatalog.find("anthropic")?.protocol)
        assertNotNull(ProviderCatalog.customPreset)
        assertEquals(ProviderProtocol.OPENAI_COMPATIBLE, ProviderCatalog.customPreset.protocol)
    }
}
