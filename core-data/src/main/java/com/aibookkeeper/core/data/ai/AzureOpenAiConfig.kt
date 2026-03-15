package com.aibookkeeper.core.data.ai

/**
 * Holds Azure OpenAI connection parameters.
 * Provided by NetworkModule (injected from BuildConfig via AppConfigModule).
 */
data class AzureOpenAiConfig(
    val apiKey: String,
    val endpoint: String,
    val deployment: String
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && endpoint.isNotBlank()
}
