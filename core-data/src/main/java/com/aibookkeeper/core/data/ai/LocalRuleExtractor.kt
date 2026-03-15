package com.aibookkeeper.core.data.ai

import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.model.ExtractionSource
import java.time.LocalDate
import javax.inject.Inject

/**
 * Offline fallback extractor using regex patterns.
 * Used when network is unavailable or AI call times out.
 */
class LocalRuleExtractor @Inject constructor() : AiExtractor {

    private val amountPattern = Regex("""(\d+\.?\d*)\s*(元|块|¥)?""")
    private val incomeKeywords = listOf("收到", "工资", "奖金", "红包", "收入", "进账")

    override suspend fun extract(input: String): Result<ExtractionResult> = runCatching {
        val amount = amountPattern.find(input)?.groupValues?.get(1)?.toDoubleOrNull()
        val isIncome = incomeKeywords.any { input.contains(it) }
        val type = if (isIncome) "INCOME" else "EXPENSE"
        val category = guessCategory(input, isIncome)

        ExtractionResult(
            amount = amount,
            type = type,
            category = category,
            date = LocalDate.now().toString(),
            note = input,
            confidence = 0.5f,
            source = ExtractionSource.LOCAL_RULE
        )
    }

    override suspend fun extractFromOcr(ocrText: String): Result<ExtractionResult> =
        extract(ocrText)

    private fun guessCategory(input: String, isIncome: Boolean): String {
        if (isIncome) {
            return when {
                input.contains("工资") -> "工资"
                input.contains("奖金") -> "奖金"
                input.contains("红包") -> "红包"
                else -> "其他"
            }
        }
        // Specific categories must be checked before the broad "购物" pattern (买/购),
        // otherwise "买药","买书","买衣服" would all match "购物" instead of their specific category.
        return when {
            input.contains("吃") || input.contains("饭") || input.contains("餐") ||
                input.contains("外卖") || input.contains("咖啡") || input.contains("奶茶") -> "餐饮"
            input.contains("打车") || input.contains("地铁") || input.contains("公交") ||
                input.contains("出租") || input.contains("加油") -> "交通"
            input.contains("医") || input.contains("药") || input.contains("医院") -> "医疗"
            input.contains("学") || input.contains("课") || input.contains("书") -> "教育"
            input.contains("衣") || input.contains("裤") || input.contains("鞋") -> "服饰"
            input.contains("电影") || input.contains("游戏") || input.contains("娱乐") -> "娱乐"
            input.contains("房租") || input.contains("水电") || input.contains("物业") -> "居住"
            input.contains("话费") || input.contains("流量") || input.contains("通讯") -> "通讯"
            input.contains("买") || input.contains("购") || input.contains("超市") -> "购物"
            else -> "其他"
        }
    }
}
