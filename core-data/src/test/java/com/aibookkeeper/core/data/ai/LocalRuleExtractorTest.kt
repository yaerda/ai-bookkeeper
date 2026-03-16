package com.aibookkeeper.core.data.ai

import com.aibookkeeper.core.data.model.ExtractionSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

class LocalRuleExtractorTest {

    private lateinit var extractor: LocalRuleExtractor

    private val todayStr = LocalDate.now().toString()

    @BeforeEach
    fun setUp() {
        extractor = LocalRuleExtractor()
    }

    // ── Amount extraction ──

    @Test
    fun should_extractAmount_when_inputContainsInteger() = runTest {
        val result = extractor.extract("午饭花了35元").getOrThrow()
        assertEquals(35.0, result.amount)
    }

    @Test
    fun should_extractAmount_when_inputContainsDecimal() = runTest {
        val result = extractor.extract("咖啡28.5块").getOrThrow()
        assertEquals(28.5, result.amount)
    }

    @Test
    fun should_returnNullAmount_when_noNumberInInput() = runTest {
        val result = extractor.extract("吃了一顿饭").getOrThrow()
        assertNull(result.amount)
    }

    // ── Type detection ──

    @Test
    fun should_returnExpenseType_when_noIncomeKeyword() = runTest {
        val result = extractor.extract("午饭35元").getOrThrow()
        assertEquals("EXPENSE", result.type)
    }

    @Test
    fun should_returnIncomeType_when_containsIncomeKeyword() = runTest {
        val keywords = listOf("收到", "工资", "奖金", "红包", "收入", "进账")
        for (keyword in keywords) {
            val result = extractor.extract("${keyword}5000元").getOrThrow()
            assertEquals("INCOME", result.type, "Failed for keyword: $keyword")
        }
    }

    // ── Category classification ──

    @Test
    fun should_categorizeAsFood_when_inputContainsFoodKeyword() = runTest {
        val foodInputs = listOf("吃饭30", "午饭20", "餐厅50", "外卖25", "咖啡28", "奶茶15")
        for (input in foodInputs) {
            val result = extractor.extract(input).getOrThrow()
            assertEquals("餐饮", result.category, "Failed for input: $input")
        }
    }

    @Test
    fun should_categorizeAsTransport_when_inputContainsTransportKeyword() = runTest {
        val transportInputs = listOf("打车20", "地铁5", "公交2", "出租车30", "加油200")
        for (input in transportInputs) {
            val result = extractor.extract(input).getOrThrow()
            assertEquals("交通", result.category, "Failed for input: $input")
        }
    }

    @Test
    fun should_categorizeAsShopping_when_inputContainsShoppingKeyword() = runTest {
        val inputs = listOf("买东西200", "购物500", "超市120")
        for (input in inputs) {
            val result = extractor.extract(input).getOrThrow()
            assertEquals("购物", result.category, "Failed for input: $input")
        }
    }

    @Test
    fun should_categorizeAsEntertainment_when_inputContainsEntertainmentKeyword() = runTest {
        val inputs = listOf("电影票50", "游戏充值100", "娱乐消费80")
        for (input in inputs) {
            val result = extractor.extract(input).getOrThrow()
            assertEquals("娱乐", result.category, "Failed for input: $input")
        }
    }

    @Test
    fun should_categorizeAsHousing_when_inputContainsHousingKeyword() = runTest {
        val inputs = listOf("房租2000", "水电费200", "物业费300")
        for (input in inputs) {
            val result = extractor.extract(input).getOrThrow()
            assertEquals("居住", result.category, "Failed for input: $input")
        }
    }

    @Test
    fun should_categorizeAsMedical_when_inputContainsMedicalKeyword() = runTest {
        val inputs = listOf("看医生100", "买药50", "去医院200")
        for (input in inputs) {
            val result = extractor.extract(input).getOrThrow()
            assertEquals("医疗", result.category, "Failed for input: $input")
        }
    }

    @Test
    fun should_categorizeAsEducation_when_inputContainsEducationKeyword() = runTest {
        val inputs = listOf("学费5000", "报课程2000", "买书100")
        for (input in inputs) {
            val result = extractor.extract(input).getOrThrow()
            assertEquals("教育", result.category, "Failed for input: $input")
        }
    }

    @Test
    fun should_categorizeAsTelecom_when_inputContainsTelecomKeyword() = runTest {
        val inputs = listOf("话费50", "流量包30", "通讯费80")
        for (input in inputs) {
            val result = extractor.extract(input).getOrThrow()
            assertEquals("通讯", result.category, "Failed for input: $input")
        }
    }

    @Test
    fun should_categorizeAsClothing_when_inputContainsClothingKeyword() = runTest {
        val inputs = listOf("买衣服300", "裤子200", "新鞋500")
        for (input in inputs) {
            val result = extractor.extract(input).getOrThrow()
            assertEquals("服饰", result.category, "Failed for input: $input")
        }
    }

    @Test
    fun should_returnOtherCategory_when_noCategoryMatch() = runTest {
        val result = extractor.extract("转账100给朋友").getOrThrow()
        assertEquals("其他", result.category)
    }

    @Test
    fun should_matchCustomFruitCategory_when_inputContainsFruitKeyword() = runTest {
        val result = extractor.extract("草莓18元", listOf("水果", "餐饮")).getOrThrow()
        assertEquals("水果", result.category)
    }

    @Test
    fun should_matchCustomFruitCategory_when_inputContainsCherryTomatoKeyword() = runTest {
        val result = extractor.extract("小番茄12元", listOf("水果", "餐饮")).getOrThrow()
        assertEquals("水果", result.category)
    }

    @Test
    fun should_matchCustomIngredientCategory_when_inputContainsVegetableKeyword() = runTest {
        val result = extractor.extract("空心菜12元", listOf("食材", "餐饮")).getOrThrow()
        assertEquals("食材", result.category)
    }

    @Test
    fun should_matchSpecificVegetableCategory_beforeGenericIngredientCategory() = runTest {
        val result = extractor.extract("空心菜12元", listOf("食材", "蔬菜", "餐饮")).getOrThrow()
        assertEquals("蔬菜", result.category)
    }

    @Test
    fun should_keepDiningCategory_when_inputLooksLikePreparedDish() = runTest {
        val result = extractor.extract("空心菜炒牛肉20元", listOf("食材", "餐饮")).getOrThrow()
        assertEquals("餐饮", result.category)
    }

    @Test
    fun should_matchCustomDrinkCategory_when_inputContainsTeaKeyword() = runTest {
        val result = extractor.extract("茶叶88元", listOf("饮料", "餐饮")).getOrThrow()
        assertEquals("饮料", result.category)
    }

    @Test
    fun should_matchCustomDrinkCategory_when_inputContainsMilkTeaKeyword() = runTest {
        val result = extractor.extract("奶茶18元", listOf("饮料", "餐饮")).getOrThrow()
        assertEquals("饮料", result.category)
    }

    // ── Income category ──

    @Test
    fun should_categorizeAsSalary_when_incomeWithSalaryKeyword() = runTest {
        val result = extractor.extract("工资5000").getOrThrow()
        assertEquals("INCOME", result.type)
        assertEquals("工资", result.category)
    }

    @Test
    fun should_categorizeAsBonus_when_incomeWithBonusKeyword() = runTest {
        val result = extractor.extract("奖金2000").getOrThrow()
        assertEquals("INCOME", result.type)
        assertEquals("奖金", result.category)
    }

    @Test
    fun should_categorizeAsRedPacket_when_incomeWithRedPacketKeyword() = runTest {
        val result = extractor.extract("红包200").getOrThrow()
        assertEquals("INCOME", result.type)
        assertEquals("红包", result.category)
    }

    @Test
    fun should_categorizeAsOther_when_incomeWithNoSpecificKeyword() = runTest {
        val result = extractor.extract("收到100").getOrThrow()
        assertEquals("INCOME", result.type)
        assertEquals("其他", result.category)
    }

    // ── Metadata ──

    @Test
    fun should_setSourceToLocalRule_when_extractionSucceeds() = runTest {
        val result = extractor.extract("午饭20").getOrThrow()
        assertEquals(ExtractionSource.LOCAL_RULE, result.source)
    }

    @Test
    fun should_calculateNonDefaultConfidence_when_extracting() = runTest {
        val result = extractor.extract("午饭20").getOrThrow()
        assertEquals(0.62f, result.confidence)
    }

    @Test
    fun should_useTodayDate_when_extracting() = runTest {
        val result = extractor.extract("午饭20").getOrThrow()
        assertEquals(todayStr, result.date)
    }

    // ── Date parsing ──

    @Test
    fun should_parseToday_when_inputContainsToday() = runTest {
        val result = extractor.extract("今天午饭35元").getOrThrow()
        assertEquals(todayStr, result.date)
    }

    @Test
    fun should_parseYesterday_when_inputContainsYesterday() = runTest {
        val result = extractor.extract("昨天打车20元").getOrThrow()
        assertEquals(LocalDate.now().minusDays(1).toString(), result.date)
    }

    @Test
    fun should_parseDayBeforeYesterday_when_inputContainsQiantian() = runTest {
        val result = extractor.extract("前天买菜50元").getOrThrow()
        assertEquals(LocalDate.now().minusDays(2).toString(), result.date)
    }

    @Test
    fun should_parseThreeDaysAgo_when_inputContainsDaqiantian() = runTest {
        val result = extractor.extract("大前天聚餐200元").getOrThrow()
        assertEquals(LocalDate.now().minusDays(3).toString(), result.date)
    }

    @Test
    fun should_parseMonthDay_when_inputContainsMonthDay() = runTest {
        val today = LocalDate.now()
        val targetMonth = today.monthValue
        val targetDay = today.dayOfMonth
        val input = "${targetMonth}月${targetDay}日吃饭30元"
        val result = extractor.extract(input).getOrThrow()
        assertEquals(today.toString(), result.date)
    }

    @Test
    fun should_parseMonthDayHao_when_inputContainsHao() = runTest {
        val today = LocalDate.now()
        val input = "${today.monthValue}月${today.dayOfMonth}号午饭20元"
        val result = extractor.extract(input).getOrThrow()
        assertEquals(today.toString(), result.date)
    }

    @Test
    fun should_parseFullChineseDate_when_yearMonthDay() = runTest {
        val result = extractor.extract("2026年1月15日买书100元").getOrThrow()
        assertEquals("2026-01-15", result.date)
    }

    @Test
    fun should_parseIsoDate_when_inputContainsDash() = runTest {
        val result = extractor.extract("2026-02-14情人节晚餐300元").getOrThrow()
        assertEquals("2026-02-14", result.date)
    }

    @Test
    fun should_parseIsoDate_when_inputContainsSlash() = runTest {
        val result = extractor.extract("2026/01/01元旦聚餐500元").getOrThrow()
        assertEquals("2026-01-01", result.date)
    }

    @Test
    fun should_useTodayDate_when_noDateInInput() = runTest {
        val result = extractor.extract("咖啡28元").getOrThrow()
        assertEquals(todayStr, result.date)
    }

    @Test
    fun should_parseDateWithParseDate_directly() {
        val today = LocalDate.of(2026, 3, 15) // A Sunday
        // Relative days
        assertEquals(today, extractor.parseDate("今天吃饭", today))
        assertEquals(today.minusDays(1), extractor.parseDate("昨天打车", today))
        assertEquals(today.minusDays(2), extractor.parseDate("前天买东西", today))
        assertEquals(today.minusDays(3), extractor.parseDate("大前天聚餐", today))

        // Month/day
        assertEquals(LocalDate.of(2026, 3, 10), extractor.parseDate("3月10日吃饭", today))
        assertEquals(LocalDate.of(2026, 3, 10), extractor.parseDate("3月10号午餐", today))

        // Full date
        assertEquals(LocalDate.of(2026, 1, 15), extractor.parseDate("2026年1月15日买书", today))

        // ISO-like
        assertEquals(LocalDate.of(2026, 2, 14), extractor.parseDate("2026-02-14晚餐", today))
        assertEquals(LocalDate.of(2026, 1, 1), extractor.parseDate("2026/01/01元旦", today))

        // No date
        assertNull(extractor.parseDate("咖啡28元", today))
    }

    @Test
    fun should_parseWeekday_when_inputContainsZhouX() {
        // 2026-03-15 is a Sunday
        val today = LocalDate.of(2026, 3, 15)
        // "周一" → most recent Monday = 2026-03-09
        assertEquals(LocalDate.of(2026, 3, 9), extractor.parseDate("周一开会", today))
        // "周日" → today (Sunday) itself
        assertEquals(LocalDate.of(2026, 3, 15), extractor.parseDate("周日聚餐", today))
        // "周六" → yesterday (Saturday)
        assertEquals(LocalDate.of(2026, 3, 14), extractor.parseDate("周六看电影", today))
    }

    @Test
    fun should_parseLastWeek_when_inputContainsShangZhou() {
        val today = LocalDate.of(2026, 3, 15) // Sunday
        // "上周一" → Monday of the previous week = 2026-03-02
        assertEquals(LocalDate.of(2026, 3, 2), extractor.parseDate("上周一出差", today))
        // "上周日" → Sunday of the previous week = 2026-03-08
        assertEquals(LocalDate.of(2026, 3, 8), extractor.parseDate("上周日聚餐", today))
    }

    @Test
    fun should_parseThisWeek_when_inputContainsZheZhou() {
        val today = LocalDate.of(2026, 3, 12) // Thursday
        // "这周一" → 2026-03-09
        assertEquals(LocalDate.of(2026, 3, 9), extractor.parseDate("这周一午饭", today))
        // "这周五" → 2026-03-13
        assertEquals(LocalDate.of(2026, 3, 13), extractor.parseDate("这周五聚餐", today))
    }

    @Test
    fun should_setNoteToOriginalInput_when_extracting() = runTest {
        val input = "星巴克咖啡35元"
        val result = extractor.extract(input).getOrThrow()
        assertEquals(input, result.note)
    }

    // ── OCR path ──

    @Test
    fun should_delegateToExtract_when_extractFromOcrCalled() = runTest {
        val result = extractor.extractFromOcr("打车30元").getOrThrow()
        assertEquals(30.0, result.amount)
        assertEquals("交通", result.category)
        assertEquals(ExtractionSource.LOCAL_RULE, result.source)
    }

    // ── Edge cases ──

    @Test
    fun should_preferExplicitTotal_when_quantityAndTotalAreBothPresent() = runTest {
        val result = extractor.extract("买了3个苹果一共15元").getOrThrow()
        assertEquals(15.0, result.amount)
    }

    @Test
    fun should_sumMultipleLineItemAmounts_when_sameLineContainsMoreThanOneAmount() = runTest {
        val result = extractor.extract("奶茶10咖啡20").getOrThrow()
        assertEquals(30.0, result.amount)
    }

    @Test
    fun should_returnSuccess_when_emptyInput() = runTest {
        val result = extractor.extract("")
        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow().amount)
    }

    // ── Regex priority regression tests ──
    // Ensures specific categories are matched before the broad "购物" (买/购) pattern.

    @Test
    fun should_categorizeAsMedical_when_inputContainsBuyAndMedicine() = runTest {
        val inputs = mapOf(
            "买药50" to "医疗",
            "购药80" to "医疗",
            "买了些感冒药30" to "医疗",
            "网购医用口罩25" to "医疗",
        )
        for ((input, expected) in inputs) {
            val result = extractor.extract(input).getOrThrow()
            assertEquals(expected, result.category, "Failed for input: '$input' — expected '$expected' but got '${result.category}'")
        }
    }

    @Test
    fun should_categorizeAsEducation_when_inputContainsBuyAndBook() = runTest {
        val inputs = mapOf(
            "买书100" to "教育",
            "购书200" to "教育",
            "买了一本新书60" to "教育",
            "网购课程资料150" to "教育",
        )
        for ((input, expected) in inputs) {
            val result = extractor.extract(input).getOrThrow()
            assertEquals(expected, result.category, "Failed for input: '$input' — expected '$expected' but got '${result.category}'")
        }
    }

    @Test
    fun should_categorizeAsClothing_when_inputContainsBuyAndClothes() = runTest {
        val inputs = mapOf(
            "买衣服300" to "服饰",
            "购衣服400" to "服饰",
            "买了条裤子250" to "服饰",
            "网购新鞋600" to "服饰",
        )
        for ((input, expected) in inputs) {
            val result = extractor.extract(input).getOrThrow()
            assertEquals(expected, result.category, "Failed for input: '$input' — expected '$expected' but got '${result.category}'")
        }
    }

    @Test
    fun should_categorizeAsShopping_when_inputHasOnlyBuyWithoutSpecificKeyword() = runTest {
        val inputs = listOf("买东西200", "购物500", "超市120", "买了个充电宝99")
        for (input in inputs) {
            val result = extractor.extract(input).getOrThrow()
            assertEquals("购物", result.category, "Failed for input: '$input'")
        }
    }

    @Test
    fun should_categorizeAsFood_when_inputContainsFoodItemKeyword() = runTest {
        val inputs = mapOf(
            "买芒果28块" to "餐饮",
            "买苹果10元" to "餐饮",
            "买牛奶15" to "餐饮",
            "买水果50" to "餐饮",
            "买鸡蛋12" to "餐饮",
            "买面包8" to "餐饮",
            "买猪肉35" to "餐饮",
            "买了点零食20" to "餐饮",
            "购蔬菜25" to "餐饮",
            "买饮料6" to "餐饮",
            "买西瓜30" to "餐饮",
            "买了一箱啤酒60" to "餐饮",
        )
        for ((input, expected) in inputs) {
            val result = extractor.extract(input).getOrThrow()
            assertEquals(expected, result.category, "Failed for input: '$input' — expected '$expected' but got '${result.category}'")
        }
    }

    @Test
    fun should_prioritizeSpecificCategory_when_multipleOverlappingKeywords() = runTest {
        // "买药" contains both 买(购物) and 药(医疗) — should pick 医疗
        assertEquals("医疗", extractor.extract("买药").getOrThrow().category)
        // "买书" contains both 买(购物) and 书(教育) — should pick 教育
        assertEquals("教育", extractor.extract("买书").getOrThrow().category)
        // "买衣" contains both 买(购物) and 衣(服饰) — should pick 服饰
        assertEquals("服饰", extractor.extract("买衣").getOrThrow().category)
        // "购鞋" contains both 购(购物) and 鞋(服饰) — should pick 服饰
        assertEquals("服饰", extractor.extract("购鞋").getOrThrow().category)
    }
}
