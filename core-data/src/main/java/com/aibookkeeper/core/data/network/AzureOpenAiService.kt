package com.aibookkeeper.core.data.network

import com.aibookkeeper.core.data.network.dto.ChatCompletionRequest
import com.aibookkeeper.core.data.network.dto.ChatCompletionResponse
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for Azure OpenAI Chat Completions API.
 *
 * Base URL is the Azure OpenAI resource endpoint,
 * e.g. https://<resource>.openai.azure.com/
 */
interface AzureOpenAiService {

    @POST("openai/deployments/{deployment}/chat/completions")
    suspend fun chatCompletions(
        @Path("deployment") deployment: String,
        @Query("api-version") apiVersion: String = "2024-06-01",
        @Header("api-key") apiKey: String,
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse
}
