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

    // Common food/grocery item keywords — matched before the generic "买/购" pattern
    // so that "买芒果", "买牛奶" etc. are categorized as "餐饮" instead of "购物".
    private val foodItemKeywords = listOf(
        // Fruits
        "芒果", "苹果", "香蕉", "橘子", "橙子", "西瓜", "葡萄", "草莓", "樱桃",
        "桃子", "梨", "柠檬", "荔枝", "龙眼", "榴莲", "火龙果", "猕猴桃", "蓝莓",
        "柚子", "菠萝", "哈密瓜", "山竹", "百香果", "石榴", "杨梅", "枇杷",
        // Vegetables
        "蔬菜", "青菜", "白菜", "番茄", "西红柿", "土豆", "黄瓜", "茄子", "萝卜",
        "洋葱", "辣椒", "豆腐", "豆芽", "菠菜", "芹菜", "花菜", "西兰花", "玉米",
        "蘑菇", "木耳",
        // Meat & protein
        "猪肉", "牛肉", "羊肉", "鸡肉", "鸡蛋", "鸭肉", "鱼", "虾", "蟹",
        "排骨", "五花肉", "肉",
        // Staples & dairy
        "米", "面粉", "面条", "面包", "馒头", "包子", "饺子", "粽子",
        "牛奶", "酸奶", "豆浆",
        // Snacks & drinks
        "零食", "饼干", "薯片", "巧克力", "糖果", "坚果", "瓜子", "花生",
        "饮料", "果汁", "可乐", "雪碧", "矿泉水", "茶叶", "啤酒", "酒",
        // Generic food terms
        "水果", "食品", "食材", "菜", "早餐", "午餐", "晚餐", "夜宵", "小吃",
        "烧烤", "火锅", "快餐", "便当", "蛋糕", "甜品", "冰淇淋", "粥",
    )

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
            foodItemKeywords.any { input.contains(it) } -> "餐饮"
            input.contains("买") || input.contains("购") || input.contains("超市") -> "购物"
            else -> "其他"
        }
    }
}
