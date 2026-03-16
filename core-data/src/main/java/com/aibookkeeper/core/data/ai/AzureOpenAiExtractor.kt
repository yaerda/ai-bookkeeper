package com.aibookkeeper.core.data.ai

import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.model.ExtractionSource
import com.aibookkeeper.core.data.network.AzureOpenAiService
import com.aibookkeeper.core.data.network.dto.AiExtractionDto
import com.aibookkeeper.core.data.network.dto.ChatCompletionRequest
import com.aibookkeeper.core.data.network.dto.ChatMessage
import com.aibookkeeper.core.data.network.dto.ResponseFormat
import com.aibookkeeper.core.data.security.SecureConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject

/**
 * Online AI extractor that calls Azure OpenAI Chat Completions
 * to parse natural-language transaction text into structured data.
 */
class AzureOpenAiExtractor @Inject constructor(
    private val service: AzureOpenAiService,
    private val config: AzureOpenAiConfig,
    private val json: Json,
    private val secureConfigStore: SecureConfigStore
) : AiExtractor {

    companion object {
        private const val OCR_SYSTEM_PROMPT_SUFFIX = """
            
            注意：以下文字来自 OCR 识别，可能包含识别错误。请尽量修正明显的 OCR 错误后再提取。
        """
    }

    override suspend fun extract(input: String, categoryNames: List<String>): Result<ExtractionResult> =
        callAi(input, AzureOpenAiPromptBuilder.buildSystemPrompt(categoryNames, secureConfigStore.getTextPrompt()))

    override suspend fun extractFromOcr(ocrText: String, categoryNames: List<String>): Result<ExtractionResult> =
        callAi(
            ocrText,
            AzureOpenAiPromptBuilder.buildSystemPrompt(categoryNames, secureConfigStore.getTextPrompt()) +
                OCR_SYSTEM_PROMPT_SUFFIX
        )

    private suspend fun callAi(
        userInput: String,
        systemPrompt: String
    ): Result<ExtractionResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.isConfigured) { "Azure OpenAI is not configured" }

            val todayStr = LocalDate.now().toString()
            val enrichedSystemPrompt = "$systemPrompt\n今天的日期是 $todayStr。"

            val request = ChatCompletionRequest(
                messages = listOf(
                    ChatMessage(role = "system", content = enrichedSystemPrompt),
                    ChatMessage(role = "user", content = userInput)
                ),
                temperature = 0.1,
                maxTokens = 512,
                responseFormat = ResponseFormat(type = "json_object")
            )

            val url = "${config.normalizedEndpoint}/openai/deployments/${config.deployment}/chat/completions?api-version=2025-01-01-preview"
            val response = service.chatCompletions(
                url = url,
                apiKey = config.apiKey,
                request = request
            )

            val content = response.choices.firstOrNull()?.message?.content
                ?: throw IllegalStateException("Empty AI response")

            val dto = json.decodeFromString<AiExtractionDto>(normalizeJsonContent(content))

            ExtractionResult(
                amount = dto.amount,
                type = dto.type,
                category = dto.category,
                merchantName = dto.merchantName,
                date = dto.date ?: todayStr,
                note = dto.note,
                confidence = dto.confidence,
                source = ExtractionSource.AZURE_AI
            )
        }
    }

    private fun normalizeJsonContent(content: String): String {
        val trimmed = content.trim()
        val withoutCodeFence = if (trimmed.startsWith("```")) {
            trimmed
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        } else {
            trimmed
        }

        val firstBrace = withoutCodeFence.indexOf('{')
        val lastBrace = withoutCodeFence.lastIndexOf('}')
        return if (firstBrace >= 0 && lastBrace > firstBrace) {
            withoutCodeFence.substring(firstBrace, lastBrace + 1)
        } else {
            withoutCodeFence
        }
    }
}
