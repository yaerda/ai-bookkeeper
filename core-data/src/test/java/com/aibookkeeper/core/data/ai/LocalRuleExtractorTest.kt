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
        val inputs = listOf("买了件衣服200", "购物500", "超市120")
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
    fun should_setConfidenceTo05_when_extracting() = runTest {
        val result = extractor.extract("午饭20").getOrThrow()
        assertEquals(0.5f, result.confidence)
    }

    @Test
    fun should_useTodayDate_when_extracting() = runTest {
        val result = extractor.extract("午饭20").getOrThrow()
        assertEquals(todayStr, result.date)
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
    fun should_extractFirstAmount_when_multipleNumbersPresent() = runTest {
        val result = extractor.extract("买了3个苹果一共15元").getOrThrow()
        // regex finds first match: "3" (from "3个")
        assertEquals(3.0, result.amount)
    }

    @Test
    fun should_returnSuccess_when_emptyInput() = runTest {
        val result = extractor.extract("")
        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow().amount)
    }
}
