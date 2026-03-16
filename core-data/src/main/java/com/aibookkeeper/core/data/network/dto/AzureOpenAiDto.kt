package com.aibookkeeper.core.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ── Request ──

@Serializable
data class ChatCompletionRequest(
    val messages: List<ChatMessage>,
    val temperature: Double = 0.1,
    @SerialName("max_completion_tokens") val maxTokens: Int = 512,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

/**
 * Chat message with multimodal content (text + images) for vision models.
 */
@Serializable
data class VisionChatMessage(
    val role: String,
    val content: List<ContentPart>
)

@Serializable
data class ContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: ImageUrlContent? = null
)

@Serializable
data class ImageUrlContent(
    val url: String
)

@Serializable
data class VisionChatCompletionRequest(
    val messages: List<@Serializable VisionChatMessageWrapper>,
    val temperature: Double = 0.1,
    @SerialName("max_completion_tokens") val maxTokens: Int = 1024,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null
)

/**
 * Wrapper that allows mixing system (string content) and user (array content) messages.
 * Uses JsonElement for polymorphic content field.
 */
@Serializable
data class VisionChatMessageWrapper(
    val role: String,
    val content: JsonElement
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

/**
 * Extended vision extraction DTO — AI returns formatted text + summary + individual items.
 */
@Serializable
data class VisionExtractionDto(
    @SerialName("formatted_text") val formattedText: String = "",
    val amount: Double? = null,
    val type: String = "EXPENSE",
    val category: String = "其他",
    @SerialName("merchant_name") val merchantName: String? = null,
    val date: String? = null,
    val note: String? = null,
    val confidence: Float = 0.0f,
    val items: List<AiExtractionDto> = emptyList()
)

@Serializable
data class TranscriptionResponse(
    val text: String? = null,
    @SerialName("combinedPhrases") val combinedPhrases: List<CombinedPhrase>? = null
)

@Serializable
data class CombinedPhrase(
    val text: String = ""
)
