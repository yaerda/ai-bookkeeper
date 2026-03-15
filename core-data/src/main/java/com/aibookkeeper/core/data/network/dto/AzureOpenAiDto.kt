package com.aibookkeeper.core.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Request ──

@Serializable
data class ChatCompletionRequest(
    val messages: List<ChatMessage>,
    val temperature: Double = 0.1,
    @SerialName("max_tokens") val maxTokens: Int = 512,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ResponseFormat(
    val type: String = "json_object"
)

// ── Response ──

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val choices: List<Choice>,
    val usage: Usage? = null
)

@Serializable
data class Choice(
    val index: Int,
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int
)

// ── AI JSON output schema ──

@Serializable
data class AiExtractionDto(
    val amount: Double? = null,
    val type: String = "EXPENSE",
    val category: String = "其他",
    @SerialName("merchant_name") val merchantName: String? = null,
    val date: String? = null,
    val note: String? = null,
    val confidence: Float = 0.0f
)
