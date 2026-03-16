package com.aibookkeeper.core.data.network

import com.aibookkeeper.core.data.network.dto.ChatCompletionRequest
import com.aibookkeeper.core.data.network.dto.ChatCompletionResponse
import com.aibookkeeper.core.data.network.dto.TranscriptionResponse
import com.aibookkeeper.core.data.network.dto.VisionChatCompletionRequest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Url

/**
 * Retrofit interface for Azure OpenAI Chat Completions API.
 *
 * Base URL is the Azure OpenAI resource endpoint,
 * e.g. https://<resource>.openai.azure.com/
 */
interface AzureOpenAiService {

    @POST
    suspend fun chatCompletions(
        @Url url: String,
        @Header("api-key") apiKey: String,
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse

    @POST
    suspend fun visionChatCompletions(
        @Url url: String,
        @Header("api-key") apiKey: String,
        @Body request: VisionChatCompletionRequest
    ): ChatCompletionResponse

    @Multipart
    @POST
    suspend fun transcribe(
        @Url url: String,
        @Header("api-key") apiKey: String,
        @Part file: MultipartBody.Part,
        @Part("response_format") responseFormat: RequestBody,
        @Part("language") language: RequestBody,
        @Part("model") model: RequestBody? = null
    ): TranscriptionResponse
}
