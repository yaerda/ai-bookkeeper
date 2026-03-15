package com.aibookkeeper.core.data.ai

import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.model.ExtractionSource
import com.aibookkeeper.core.data.network.AzureOpenAiService
import com.aibookkeeper.core.data.network.dto.AiExtractionDto
import com.aibookkeeper.core.data.network.dto.ChatCompletionRequest
import com.aibookkeeper.core.data.network.dto.ChatMessage
import com.aibookkeeper.core.data.network.dto.ResponseFormat
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
    private val json: Json
) : AiExtractor {

    companion object {
        private val SYSTEM_PROMPT = """
            你是一个智能记账助手。用户会输入一段描述消费或收入的文字，你需要提取结构化信息并以 JSON 返回。
            
            JSON 格式如下（所有字段必须存在，值可以为 null）：
            {
              "amount": 数字或null,
              "type": "EXPENSE" 或 "INCOME",
              "category": "餐饮"|"交通"|"购物"|"娱乐"|"居住"|"医疗"|"教育"|"通讯"|"服饰"|"工资"|"奖金"|"红包"|"其他",
              "merchant_name": "商家名称或null",
              "date": "YYYY-MM-DD 或 null（如果未提及日期）",
              "note": "原始描述的简短摘要",
              "confidence": 0.0到1.0之间的浮点数，表示你对提取结果的把握程度
            }
            
            规则：
            1. amount 提取数字金额，去掉货币符号
            2. type 默认 EXPENSE，只有明确的收入关键词才用 INCOME
            3. category 从上述列表中选择最匹配的
            4. date 如果用户说"今天"/"昨天"/"前天"，转换为具体日期
            5. 只返回 JSON，不要包含任何其他文字
        """.trimIndent()

        private const val OCR_SYSTEM_PROMPT_SUFFIX = """
            
            注意：以下文字来自 OCR 识别，可能包含识别错误。请尽量修正明显的 OCR 错误后再提取。
        """
    }

    override suspend fun extract(input: String): Result<ExtractionResult> =
        callAi(input, SYSTEM_PROMPT)

    override suspend fun extractFromOcr(ocrText: String): Result<ExtractionResult> =
        callAi(ocrText, SYSTEM_PROMPT + OCR_SYSTEM_PROMPT_SUFFIX)

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

            val response = service.chatCompletions(
                deployment = config.deployment,
                apiKey = config.apiKey,
                request = request
            )

            val content = response.choices.firstOrNull()?.message?.content
                ?: throw IllegalStateException("Empty AI response")

            val dto = json.decodeFromString<AiExtractionDto>(content)

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
}
