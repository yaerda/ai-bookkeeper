package com.aibookkeeper.core.data.ai

import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.model.ExtractionSource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/**
 * Offline fallback extractor using regex patterns.
 * Used when network is unavailable or AI call times out.
 */
class LocalRuleExtractor @Inject constructor() : AiExtractor {

    private data class AmountAnalysis(
        val amount: Double?,
        val candidateCount: Int,
        val hasExplicitTotal: Boolean
    )

    private val amountPattern = Regex("""(\d+(?:\.\d+)?)\s*(元|块|快|¥)?""")
    private val explicitTotalPatterns = listOf(
        Regex("""(?:一共|总共|总计|共计|合计|付款|支付|付了|花了|花费|消费|支出|收入|收到|进账|赚了)\s*(\d+(?:\.\d+)?)\s*(元|块|快|¥)?"""),
        Regex("""(?:一共|总共|总计|共计|合计)[^\d]{0,4}(\d+(?:\.\d+)?)\s*(元|块|快|¥)?""")
    )
    private val quantityUnits = listOf(
        "个", "件", "斤", "公斤", "kg", "g", "瓶", "包", "盒", "杯", "份",
        "次", "张", "支", "袋", "串", "颗", "只", "台", "双", "桶", "升", "ml", "mL", "l", "L"
    )
    private val incomeKeywords = listOf("收到", "工资", "奖金", "红包", "收入", "进账")

    override suspend fun extract(input: String, categoryNames: List<String>): Result<ExtractionResult> = runCatching {
        val amountAnalysis = analyzeAmount(input)
        val isIncome = incomeKeywords.any { input.contains(it) }
        val matchedCustomCategory = matchCustomCategory(input, categoryNames)
        val type = if (isIncome) "INCOME" else "EXPENSE"
        val category = guessCategory(input, isIncome, matchedCustomCategory)
        val parsedDate = parseDate(input)
        val date = parsedDate ?: LocalDate.now()

        ExtractionResult(
            amount = amountAnalysis.amount,
            type = type,
            category = category,
            date = date.toString(),
            note = input,
            confidence = calculateConfidence(
                input = input,
                amount = amountAnalysis.amount,
                category = category,
                hasCustomCategoryMatch = matchedCustomCategory != null,
                hasParsedDate = parsedDate != null,
                amountCandidateCount = amountAnalysis.candidateCount,
                hasExplicitTotal = amountAnalysis.hasExplicitTotal
            ),
            source = ExtractionSource.LOCAL_RULE
        )
    }

    override suspend fun extractFromOcr(ocrText: String, categoryNames: List<String>): Result<ExtractionResult> =
        extract(ocrText, categoryNames)

    private val fruitKeywords = listOf(
        "芒果", "苹果", "香蕉", "橘子", "橙子", "西瓜", "葡萄", "草莓", "樱桃",
        "桃子", "梨", "柠檬", "荔枝", "龙眼", "榴莲", "火龙果", "猕猴桃", "蓝莓",
        "柚子", "菠萝", "哈密瓜", "山竹", "百香果", "石榴", "杨梅", "枇杷", "小番茄"
    )

    private val vegetableKeywords = listOf(
        "蔬菜", "青菜", "白菜", "番茄", "西红柿", "土豆", "黄瓜", "茄子", "萝卜",
        "洋葱", "辣椒", "豆腐", "豆芽", "菠菜", "芹菜", "花菜", "西兰花", "玉米",
        "蘑菇", "木耳"
    )

    private val proteinKeywords = listOf(
        "猪肉", "牛肉", "羊肉", "鸡肉", "鸡蛋", "鸭肉", "鱼", "虾", "蟹",
        "排骨", "五花肉", "肉"
    )

    private val stapleAndDairyKeywords = listOf(
        "米", "面粉", "面条", "面包", "馒头", "包子", "饺子", "粽子",
        "牛奶", "酸奶", "豆浆"
    )

    private val snackAndDrinkKeywords = listOf(
        "零食", "饼干", "薯片", "巧克力", "糖果", "坚果", "瓜子", "花生",
        "饮料", "果汁", "可乐", "雪碧", "矿泉水", "茶叶", "啤酒", "酒"
    )

    private val genericFoodKeywords = listOf(
        "水果", "食品", "食材", "菜", "早餐", "午餐", "晚餐", "夜宵", "小吃",
        "烧烤", "火锅", "快餐", "便当", "蛋糕", "甜品", "冰淇淋", "粥"
    )

    // Common food/grocery item keywords — matched before the generic "买/购" pattern
    // so that "买芒果", "买牛奶" etc. are categorized as food-related instead of "购物".
    private val foodItemKeywords = fruitKeywords +
        vegetableKeywords +
        proteinKeywords +
        stapleAndDairyKeywords +
        snackAndDrinkKeywords +
        genericFoodKeywords

    private fun guessCategory(input: String, isIncome: Boolean, matchedCustomCategory: String?): String {
        if (isIncome) {
            return when {
                input.contains("工资") -> "工资"
                input.contains("奖金") -> "奖金"
                input.contains("红包") -> "红包"
                else -> "其他"
            }
        }
        matchedCustomCategory?.let { return it }
        // Specific categories must be checked before the broad "购物" pattern (买/购)
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
            foodItemKeywords.any { input.contains(it) } -> "餐饮"
            input.contains("买") || input.contains("购") || input.contains("超市") -> "购物"
            else -> "其他"
        }
    }

    private fun matchCustomCategory(input: String, customCategories: List<String>): String? =
        CategorySemanticMatcher.findBestMatchingCategory(input, customCategories)

    private fun analyzeAmount(input: String): AmountAnalysis {
        findExplicitTotalAmount(input)?.let { total ->
            return AmountAnalysis(amount = total, candidateCount = 1, hasExplicitTotal = true)
        }

        val candidates = amountPattern.findAll(input)
            .mapNotNull { match ->
                val numberGroup = match.groups[1] ?: return@mapNotNull null
                val value = numberGroup.value.toDoubleOrNull() ?: return@mapNotNull null
                val unit = match.groups[2]?.value.orEmpty()
                if (looksLikeDateToken(input, numberGroup.range)) return@mapNotNull null
                if (unit.isBlank() && looksLikeQuantityToken(input, numberGroup.range)) return@mapNotNull null
                value
            }
            .toList()

        if (candidates.isEmpty()) {
            return AmountAnalysis(amount = null, candidateCount = 0, hasExplicitTotal = false)
        }

        val resolvedAmount = if (candidates.size == 1) candidates.first() else candidates.sum()
        return AmountAnalysis(
            amount = resolvedAmount,
            candidateCount = candidates.size,
            hasExplicitTotal = false
        )
    }

    private fun findExplicitTotalAmount(input: String): Double? {
        explicitTotalPatterns.forEach { pattern ->
            pattern.find(input)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private fun looksLikeDateToken(input: String, numberRange: IntRange): Boolean {
        val previousChar = input.getOrNull(numberRange.first - 1)
        val nextChar = input.getOrNull(numberRange.last + 1)
        return previousChar in setOf('年', '月', '-', '/')
            || nextChar in setOf('年', '月', '日', '号', '-', '/')
    }

    private fun looksLikeQuantityToken(input: String, numberRange: IntRange): Boolean {
        val suffix = input.substring((numberRange.last + 1).coerceAtMost(input.length)).trimStart()
        return quantityUnits.any { suffix.startsWith(it) }
    }

    private fun calculateConfidence(
        input: String,
        amount: Double?,
        category: String,
        hasCustomCategoryMatch: Boolean,
        hasParsedDate: Boolean,
        amountCandidateCount: Int,
        hasExplicitTotal: Boolean
    ): Float {
        if (amount == null) {
            return 0.38f
        }

        var confidence = 0.48f
        confidence += when {
            hasExplicitTotal -> 0.12f
            amountCandidateCount > 1 -> 0.10f
            amountCandidateCount == 1 -> 0.07f
            else -> 0f
        }
        if (category != "其他") confidence += 0.05f
        if (hasCustomCategoryMatch) confidence += 0.03f
        if (hasParsedDate) confidence += 0.02f
        if (input.length in 2..40) confidence += 0.02f
        return confidence.coerceIn(0.38f, 0.68f)
    }

    // ── Date parsing ────────────────────────────────────────────────────────

    // Relative day keywords
    private val relativeDayPattern = Regex("(大前天|前天|昨天|今天)")

    // "X月X日" or "X月X号"
    private val monthDayPattern = Regex("""(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]""")

    // "YYYY年X月X日"
    private val fullDatePattern = Regex("""(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]""")

    // ISO-like: "YYYY-MM-DD" or "YYYY/MM/DD"
    private val isoDatePattern = Regex("""(\d{4})[-/](\d{1,2})[-/](\d{1,2})""")

    // "上周X" / "上个星期X"
    private val lastWeekPattern = Regex("上(?:个)?(?:周|星期)([一二三四五六日天])")

    // "这周X" / "这个星期X"
    private val thisWeekPattern = Regex("这(?:个)?(?:周|星期)([一二三四五六日天])")

    // "周X" / "星期X" (without 上/这 prefix, treat as most recent past or today)
    private val weekdayPattern = Regex("(?:周|星期)([一二三四五六日天])")

    /**
     * Try to parse a date from the input text.
     * Returns null if no recognizable date expression is found.
     */
    internal fun parseDate(input: String, today: LocalDate = LocalDate.now()): LocalDate? {
        // 1. Relative days (most common in casual Chinese input)
        relativeDayPattern.find(input)?.let { match ->
            return when (match.value) {
                "今天" -> today
                "昨天" -> today.minusDays(1)
                "前天" -> today.minusDays(2)
                "大前天" -> today.minusDays(3)
                else -> null
            }
        }

        // 2. Full date: "2026年3月15日"
        fullDatePattern.find(input)?.let { match ->
            return tryBuildDate(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt()
            )
        }

        // 3. ISO-like: "2026-03-15" or "2026/03/15"
        isoDatePattern.find(input)?.let { match ->
            return tryBuildDate(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt()
            )
        }

        // 4. "上周X"
        lastWeekPattern.find(input)?.let { match ->
            val dow = chineseDayOfWeek(match.groupValues[1]) ?: return@let
            return today.with(TemporalAdjusters.previous(dow)).let { lastOccurrence ->
                // Ensure it's actually in the previous week
                val startOfThisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                if (lastOccurrence.isBefore(startOfThisWeek)) lastOccurrence
                else lastOccurrence.minusWeeks(1)
            }
        }

        // 5. "这周X"
        thisWeekPattern.find(input)?.let { match ->
            val dow = chineseDayOfWeek(match.groupValues[1]) ?: return@let
            val startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return startOfWeek.with(TemporalAdjusters.nextOrSame(dow))
        }

        // 6. "X月X日/号" (default to current year; if future, go back one year)
        monthDayPattern.find(input)?.let { match ->
            val month = match.groupValues[1].toInt()
            val day = match.groupValues[2].toInt()
            val candidate = tryBuildDate(today.year, month, day) ?: return@let
            return if (candidate.isAfter(today)) {
                tryBuildDate(today.year - 1, month, day) ?: candidate
            } else candidate
        }

        // 7. Bare "周X"/"星期X" — most recent occurrence
        weekdayPattern.find(input)?.let { match ->
            val dow = chineseDayOfWeek(match.groupValues[1]) ?: return@let
            return today.with(TemporalAdjusters.previousOrSame(dow))
        }

        return null
    }

    private fun chineseDayOfWeek(ch: String): DayOfWeek? = when (ch) {
        "一" -> DayOfWeek.MONDAY
        "二" -> DayOfWeek.TUESDAY
        "三" -> DayOfWeek.WEDNESDAY
        "四" -> DayOfWeek.THURSDAY
        "五" -> DayOfWeek.FRIDAY
        "六" -> DayOfWeek.SATURDAY
        "日", "天" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun tryBuildDate(year: Int, month: Int, day: Int): LocalDate? {
        return try {
            LocalDate.of(year, month, day)
        } catch (_: Exception) {
            null
        }
    }
}
