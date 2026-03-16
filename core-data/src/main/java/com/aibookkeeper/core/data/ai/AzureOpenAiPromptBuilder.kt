package com.aibookkeeper.core.data.ai

object AzureOpenAiPromptBuilder {

    private val defaultCategories = listOf(
        "餐饮", "交通", "购物", "娱乐", "居住", "医疗", "教育", "通讯", "服饰",
        "工资", "奖金", "红包", "其他"
    )

    private fun categoriesString(categoryNames: List<String>): String =
        categoryNames
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString("|") { "\"$it\"" }
            .ifEmpty {
                // Fallback if DB is empty (shouldn't happen normally)
                defaultCategories.joinToString("|") { "\"$it\"" }
            }

    fun buildBaseSystemPrompt(categoryNames: List<String>): String {
        val categories = categoriesString(categoryNames)

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
             4. category 必须且只能从上述候选列表中选择，绝对不可以返回列表之外的分类名。如果没有完全匹配的，选择最接近的一个
             5. 如果文本是在购买蔬菜、水果、肉蛋奶、米面粮油等原材料/生鲜，且候选中存在“食材”“生鲜”“买菜”“蔬菜”“水果”等更贴近的分类，优先选择这些分类，而不是笼统选择“餐饮”
             6. 如果候选中存在“饮料”“饮品”“茶饮”“酒水”“咖啡”“奶茶”等更贴近的分类，像茶叶、茶包、咖啡豆、矿泉水、可乐、果汁、啤酒，以及奶茶、咖啡、饮品消费都优先归到这些分类，而不是笼统选择“餐饮”或“购物”
             7. 只有明确是堂食、外卖、餐厅、套餐、便当等现成餐食消费时，才选择“餐饮”
             8. confidence 要反映真实把握度：金额、分类、日期都明确时应给较高值（如 0.85 以上）；只有部分字段靠猜测时给中等值；只有在明显模糊时才给 0.4-0.6，避免机械地固定返回 0.5
             9. date 如果用户说"今天"/"昨天"/"前天"/"上周X"/"X月X号"，转换为具体日期
             10. 只返回 JSON，不要包含任何其他文字
        """.trimIndent()
    }

    fun buildVisionSystemPrompt(categoryNames: List<String>, customPrompt: String): String {
        val categories = categoriesString(categoryNames)

        val base = """
            你是一个智能记账助手。用户发送一张图片（支付截图、购物小票、外卖订单等），你需要识别图片内容并以 JSON 返回。

            JSON 格式如下：
            {
              "formatted_text": "从图片中识别出的内容，整理为易读的文字。如有多个商品/项目，每项单独一行，格式为'商品名 ¥金额'",
              "amount": 总金额数字,
              "type": "EXPENSE" 或 "INCOME",
              "category": $categories,
              "merchant_name": "商家名称或null",
              "date": "YYYY-MM-DD 或 null",
              "note": "简短摘要",
              "confidence": 0.0-1.0,
              "items": [
                {
                  "amount": 单项金额,
                  "type": "EXPENSE" 或 "INCOME",
                  "category": "从候选分类中选择",
                  "merchant_name": "商家名称或null",
                  "date": "YYYY-MM-DD 或 null",
                  "note": "该项商品描述",
                  "confidence": 0.0-1.0
                }
              ]
            }

            规则：
            1. formatted_text：将图片中的关键消费/收入信息整理为干净的文字，去除无关内容（订单号、手机号等）。多个商品时每个一行，格式为"商品名 ¥金额"
            2. amount：所有项目的总金额。如果图片中有"合计/总计/实付"等，优先使用
            3. items：将每个可区分的商品/项目作为一个独立条目。如果只有一笔消费，items 只含一个元素
            4. 每个 item 的 category 独立判断，可能不同（如超市小票中有食品也有日用品）
            5. category 必须且只能从候选列表中选择，绝对不可以返回列表之外的分类名
            6. 忽略无关数字（订单号、手机号、时间戳等），只关注实际支付/收入金额
            7. type 默认 EXPENSE，只有明确收入关键词才用 INCOME
            8. date 如果图片中有日期，转换为 YYYY-MM-DD 格式
            9. confidence 反映真实把握度
            10. 只返回 JSON，不要包含任何其他文字
        """.trimIndent()

        val normalizedCustomPrompt = customPrompt.trim()
        return if (normalizedCustomPrompt.isBlank()) base
        else "$base\n\n附加用户自定义规则（高优先级）：\n$normalizedCustomPrompt"
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
