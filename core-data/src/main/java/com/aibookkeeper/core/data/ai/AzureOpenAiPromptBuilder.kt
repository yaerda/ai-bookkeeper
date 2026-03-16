package com.aibookkeeper.core.data.ai

object AzureOpenAiPromptBuilder {

    private val defaultCategories = listOf(
        "餐饮", "交通", "购物", "娱乐", "居住", "医疗", "教育", "通讯", "服饰",
        "工资", "奖金", "红包", "其他"
    )

    fun buildBaseSystemPrompt(categoryNames: List<String>): String {
        val categories = (categoryNames + defaultCategories)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString("|") { "\"$it\"" }

        return """
            你是一个智能记账助手。用户会输入一段描述消费或收入的文字，你需要提取结构化信息并以 JSON 返回。
            
            JSON 格式如下（所有字段必须存在，值可以为 null）：
            {
              "amount": 数字或null,
              "type": "EXPENSE" 或 "INCOME",
              "category": $categories,
              "merchant_name": "商家名称或null",
              "date": "YYYY-MM-DD 或 null（如果未提及日期）",
              "note": "原始描述的简短摘要",
              "confidence": 0.0到1.0之间的浮点数，表示你对提取结果的把握程度
            }
            
             规则：
             1. amount 提取数字金额，去掉货币符号
             2. 如果同一条输入里出现多个商品+金额，但本质上是同一笔消费/收入（例如“奶茶10咖啡20”），amount 应该汇总为总金额 30，note 保留主要商品摘要；如果文本里已经明确给出“一共/合计/总计”，优先使用明确总额
             3. type 默认 EXPENSE，只有明确的收入关键词才用 INCOME
             4. category 必须从上述列表中选择最匹配的，优先选择用户自定义分类
             5. 如果文本是在购买蔬菜、水果、肉蛋奶、米面粮油等原材料/生鲜，且候选中存在“食材”“生鲜”“买菜”“蔬菜”“水果”等更贴近的分类，优先选择这些分类，而不是笼统选择“餐饮”
             6. 如果候选中存在“饮料”“饮品”“茶饮”“酒水”“咖啡”“奶茶”等更贴近的分类，像茶叶、茶包、咖啡豆、矿泉水、可乐、果汁、啤酒，以及奶茶、咖啡、饮品消费都优先归到这些分类，而不是笼统选择“餐饮”或“购物”
             7. 只有明确是堂食、外卖、餐厅、套餐、便当等现成餐食消费时，才选择“餐饮”
             8. confidence 要反映真实把握度：金额、分类、日期都明确时应给较高值（如 0.85 以上）；只有部分字段靠猜测时给中等值；只有在明显模糊时才给 0.4-0.6，避免机械地固定返回 0.5
             9. date 如果用户说"今天"/"昨天"/"前天"/"上周X"/"X月X号"，转换为具体日期
             10. 只返回 JSON，不要包含任何其他文字
        """.trimIndent()
    }

    fun buildSystemPrompt(
        categoryNames: List<String>,
        customPrompt: String
    ): String {
        val basePrompt = buildBaseSystemPrompt(categoryNames)
        val normalizedCustomPrompt = customPrompt.trim()
        return if (normalizedCustomPrompt.isBlank()) {
            basePrompt
        } else {
            "$basePrompt\n\n附加用户自定义规则（高优先级；在不违反 JSON 格式和候选分类约束的前提下优先遵循）：\n$normalizedCustomPrompt"
        }
    }
}
