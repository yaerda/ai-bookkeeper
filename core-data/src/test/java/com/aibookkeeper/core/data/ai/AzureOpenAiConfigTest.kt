package com.aibookkeeper.core.data.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AzureOpenAiConfigTest {

    // ── isConfigured ─────────────────────────────────────────────────────

    @Nested
    inner class IsConfigured {

        @Test
        fun should_returnTrue_when_apiKeyAndEndpointAreNotBlank() {
            val config = AzureOpenAiConfig(
                apiKey = "test-key",
                endpoint = "https://test.openai.azure.com/",
                deployment = "gpt-4o"
            )
            assertTrue(config.isConfigured)
        }

        @Test
        fun should_returnFalse_when_apiKeyIsBlank() {
            val config = AzureOpenAiConfig(
                apiKey = "",
                endpoint = "https://test.openai.azure.com/",
                deployment = "gpt-4o"
            )
            assertFalse(config.isConfigured)
        }

        @Test
        fun should_returnFalse_when_endpointIsBlank() {
            val config = AzureOpenAiConfig(
                apiKey = "test-key",
                endpoint = "",
                deployment = "gpt-4o"
            )
            assertFalse(config.isConfigured)
        }

        @Test
        fun should_returnFalse_when_bothAreBlank() {
            val config = AzureOpenAiConfig(
                apiKey = "",
                endpoint = "",
                deployment = "gpt-4o"
            )
            assertFalse(config.isConfigured)
        }

        @Test
        fun should_returnFalse_when_apiKeyIsWhitespaceOnly() {
            val config = AzureOpenAiConfig(
                apiKey = "   ",
                endpoint = "https://test.openai.azure.com/",
                deployment = "gpt-4o"
            )
            assertFalse(config.isConfigured)
        }

        @Test
        fun should_returnFalse_when_endpointIsWhitespaceOnly() {
            val config = AzureOpenAiConfig(
                apiKey = "test-key",
                endpoint = "   ",
                deployment = "gpt-4o"
            )
            assertFalse(config.isConfigured)
        }

        @Test
        fun should_returnTrue_when_deploymentIsBlankButKeyAndEndpointAreSet() {
            val config = AzureOpenAiConfig(
                apiKey = "test-key",
                endpoint = "https://test.openai.azure.com/",
                deployment = ""
            )
            assertTrue(config.isConfigured)
        }
    }

    // ── Data class behavior ──────────────────────────────────────────────

    @Nested
    inner class DataClassBehavior {

        @Test
        fun should_beEqual_when_sameValues() {
            val config1 = AzureOpenAiConfig("key", "endpoint", "deploy")
            val config2 = AzureOpenAiConfig("key", "endpoint", "deploy")
            assertEquals(config1, config2)
        }

        @Test
        fun should_notBeEqual_when_differentApiKey() {
            val config1 = AzureOpenAiConfig("key1", "endpoint", "deploy")
            val config2 = AzureOpenAiConfig("key2", "endpoint", "deploy")
            assertNotEquals(config1, config2)
        }

        @Test
        fun should_haveSameHashCode_when_equalConfigs() {
            val config1 = AzureOpenAiConfig("key", "endpoint", "deploy")
            val config2 = AzureOpenAiConfig("key", "endpoint", "deploy")
            assertEquals(config1.hashCode(), config2.hashCode())
        }
    }
}
