package com.aibookkeeper.core.data.ai

internal object CategorySemanticMatcher {

    private val fruitKeywords = listOf(
        "芒果", "苹果", "香蕉", "橘子", "橙子", "西瓜", "葡萄", "草莓", "樱桃",
        "桃子", "梨", "柠檬", "荔枝", "龙眼", "榴莲", "火龙果", "猕猴桃", "蓝莓",
        "柚子", "菠萝", "哈密瓜", "山竹", "百香果", "石榴", "杨梅", "枇杷", "小番茄"
    )

    private val vegetableKeywords = listOf(
        "蔬菜", "青菜", "白菜", "娃娃菜", "生菜", "油麦菜", "空心菜", "上海青",
        "番茄", "西红柿", "土豆", "黄瓜", "茄子", "萝卜", "洋葱", "辣椒",
        "豆腐", "豆芽", "菠菜", "芹菜", "花菜", "西兰花", "西蓝花", "玉米",
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

    private val drinkKeywords = listOf(
        "饮料", "饮品", "果汁", "可乐", "雪碧", "矿泉水", "苏打水", "气泡水",
        "茶叶", "茶包", "红茶", "绿茶", "乌龙", "普洱", "茉莉", "花茶",
        "咖啡", "咖啡豆", "咖啡粉", "奶茶", "豆浆", "啤酒", "白酒", "红酒", "酒"
    )

    private val grocerySignals = listOf(
        "买", "购", "超市", "菜场", "菜市场", "生鲜", "买菜", "盒马", "朴朴", "叮咚",
        "斤", "公斤", "kg", "克", "g", "袋", "盒", "包", "箱", "把", "棵"
    )

    private val diningSignals = listOf(
        "吃", "饭", "餐", "外卖", "堂食", "餐厅", "咖啡", "奶茶",
        "下午茶", "夜宵", "请客", "聚餐", "自助"
    )

    private val preparedDishSignals = listOf(
        "炒", "煎", "炸", "烤", "煮", "拌", "焖", "卤", "汤", "锅",
        "盖饭", "套餐", "便当", "米线", "拉面", "麻辣烫", "汉堡", "披萨", "寿司"
    )

    private val fruitCategoryAliases = listOf("水果", "鲜果")
    private val vegetableCategoryAliases = listOf("蔬菜", "青菜")
    private val ingredientCategoryAliases = listOf("食材", "生鲜", "买菜", "菜场", "菜市场", "蔬果", "杂货", "粮油")
    private val drinkCategoryAliases = listOf("饮料", "饮品", "茶饮", "酒水", "咖啡", "奶茶")
    private val diningCategoryAliases = listOf("餐饮", "外卖", "堂食", "早餐", "午餐", "晚餐", "夜宵", "咖啡", "奶茶")

    fun findBestMatchingCategory(input: String, categories: List<String>): String? {
        val normalizedInput = input.trim()
        val normalizedCategories = categories.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()

        if (normalizedInput.isBlank() || normalizedCategories.isEmpty()) {
            return null
        }

        normalizedCategories.firstOrNull { normalizedInput.contains(it) }?.let { return it }

        val hasDiningSignals = diningSignals.any(normalizedInput::contains)
        val hasPreparedDishSignals = preparedDishSignals.any(normalizedInput::contains)
        val hasGrocerySignals = grocerySignals.any(normalizedInput::contains)
        val hasFruitItem = fruitKeywords.any(normalizedInput::contains)
        val hasVegetableItem = vegetableKeywords.any(normalizedInput::contains)
        val hasDrinkItem = drinkKeywords.any(normalizedInput::contains)
        val hasIngredientItem = hasFruitItem ||
            hasVegetableItem ||
            proteinKeywords.any(normalizedInput::contains) ||
            stapleAndDairyKeywords.any(normalizedInput::contains)

        val looksLikeRawIngredient = hasIngredientItem &&
            (hasGrocerySignals || (!hasDiningSignals && !hasPreparedDishSignals))

        if (hasFruitItem && looksLikeRawIngredient) {
            normalizedCategories.firstMatchingAlias(fruitCategoryAliases)?.let { return it }
            normalizedCategories.firstMatchingAlias(ingredientCategoryAliases)?.let { return it }
        }

        if (hasVegetableItem && looksLikeRawIngredient) {
            normalizedCategories.firstMatchingAlias(vegetableCategoryAliases)?.let { return it }
            normalizedCategories.firstMatchingAlias(ingredientCategoryAliases)?.let { return it }
        }

        if (looksLikeRawIngredient) {
            normalizedCategories.firstMatchingAlias(ingredientCategoryAliases)?.let { return it }
        }

        if (hasDrinkItem) {
            normalizedCategories.firstMatchingAlias(drinkCategoryAliases)?.let { return it }
        }

        if (hasDiningSignals || hasPreparedDishSignals) {
            normalizedCategories.firstMatchingAlias(diningCategoryAliases)?.let { return it }
        }

        return null
    }

    private fun List<String>.firstMatchingAlias(aliases: List<String>): String? =
        firstOrNull { category -> aliases.any(category::contains) }
}
