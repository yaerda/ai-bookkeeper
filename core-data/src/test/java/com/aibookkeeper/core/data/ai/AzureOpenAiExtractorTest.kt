package com.aibookkeeper.core.data.ai

import com.aibookkeeper.core.data.model.ExtractionSource
import com.aibookkeeper.core.data.network.AzureOpenAiService
import com.aibookkeeper.core.data.network.dto.ChatCompletionRequest
import com.aibookkeeper.core.data.network.dto.ChatCompletionResponse
import com.aibookkeeper.core.data.network.dto.ChatMessage
import com.aibookkeeper.core.data.network.dto.Choice
import com.aibookkeeper.core.data.security.SecureConfigStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AzureOpenAiExtractorTest {

    private lateinit var service: AzureOpenAiService
    private lateinit var config: AzureOpenAiConfig
    private lateinit var json: Json
    private lateinit var secureConfigStore: SecureConfigStore
    private lateinit var extractor: AzureOpenAiExtractor

    private val todayStr = LocalDate.now().toString()

    @BeforeEach
    fun setUp() {
        service = mockk()
        secureConfigStore = mockk()
        json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
        every { secureConfigStore.getTextPrompt() } returns ""
    }

    private fun createExtractor(
        apiKey: String = "test-key",
        endpoint: String = "https://test.openai.azure.com/",
        deployment: String = "gpt-4o"
    ): AzureOpenAiExtractor {
        config = AzureOpenAiConfig(apiKey, endpoint, deployment)
        return AzureOpenAiExtractor(service, config, json, secureConfigStore)
    }

    private fun buildResponse(content: String): ChatCompletionResponse =
        ChatCompletionResponse(
            id = "chatcmpl-test",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = content),
                    finishReason = "stop"
                )
            )
        )

    // ── Happy path ──

    @Test
    fun should_returnExtractionResult_when_aiReturnsValidJson() = runTest {
        extractor = createExtractor()
        val aiJson = """
            {"amount":35.5,"type":"EXPENSE","category":"餐饮",
             "merchant_name":"星巴克","date":"$todayStr",
             "note":"咖啡","confidence":0.95}
        """.trimIndent()

        coEvery {
            service.chatCompletions(any(), any(), any())
        } returns buildResponse(aiJson)

        val result = extractor.extract("星巴克咖啡35.5元")

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertEquals(35.5, data.amount)
        assertEquals("EXPENSE", data.type)
        assertEquals("餐饮", data.category)
        assertEquals("星巴克", data.merchantName)
        assertEquals("咖啡", data.note)
        assertEquals(0.95f, data.confidence)
        assertEquals(ExtractionSource.AZURE_AI, data.source)
    }

    @Test
    fun should_setSourceToAzureAi_when_extractionSucceeds() = runTest {
        extractor = createExtractor()
        val aiJson = """{"amount":10,"type":"EXPENSE","category":"其他","confidence":0.8}"""

        coEvery {
            service.chatCompletions(any(), any(), any())
        } returns buildResponse(aiJson)

        val data = extractor.extract("买了个东西10块").getOrThrow()
        assertEquals(ExtractionSource.AZURE_AI, data.source)
    }

    @Test
    fun should_useTodayDate_when_dtoDateIsNull() = runTest {
        extractor = createExtractor()
        val aiJson = """{"amount":20,"type":"EXPENSE","category":"餐饮","confidence":0.9}"""

        coEvery {
            service.chatCompletions(any(), any(), any())
        } returns buildResponse(aiJson)

        val data = extractor.extract("午饭20元").getOrThrow()
        assertEquals(todayStr, data.date)
    }

    @Test
    fun should_returnExtractionResult_when_extractFromOcrCalled() = runTest {
        extractor = createExtractor()
        val aiJson = """
            {"amount":128,"type":"EXPENSE","category":"购物",
             "merchant_name":"沃尔玛","date":"$todayStr",
             "note":"超市购物","confidence":0.85}
        """.trimIndent()

        coEvery {
            service.chatCompletions(any(), any(), any())
        } returns buildResponse(aiJson)

        val result = extractor.extractFromOcr("沃尔玛 合计128元")

        assertTrue(result.isSuccess)
        assertEquals(128.0, result.getOrThrow().amount)
    }

    @Test
    fun should_passCorrectDeploymentAndApiKey_when_callingService() = runTest {
        extractor = createExtractor(apiKey = "my-key", deployment = "gpt-4o-mini")
        val aiJson = """{"amount":1,"type":"EXPENSE","category":"其他","confidence":0.5}"""

        val urlSlot = slot<String>()
        val apiKeySlot = slot<String>()

        coEvery {
            service.chatCompletions(
                capture(urlSlot), capture(apiKeySlot), any()
            )
        } returns buildResponse(aiJson)

        extractor.extract("test")

        assertTrue(urlSlot.captured.contains("/deployments/gpt-4o-mini/chat/completions"))
        assertEquals("my-key", apiKeySlot.captured)
    }

    @Test
    fun should_sendSystemAndUserMessages_when_callingService() = runTest {
        extractor = createExtractor()
        val aiJson = """{"amount":1,"type":"EXPENSE","category":"其他","confidence":0.5}"""

        val requestSlot = slot<ChatCompletionRequest>()

        coEvery {
            service.chatCompletions(any(), any(), capture(requestSlot))
        } returns buildResponse(aiJson)

        extractor.extract("买水果50元")

        val messages = requestSlot.captured.messages
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].role)
        assertEquals("user", messages[1].role)
        assertEquals("买水果50元", messages[1].content)
        assertTrue(messages[0].content.contains("智能记账助手"))
    }

    @Test
    fun should_includeOcrSuffix_when_extractFromOcrCalled() = runTest {
        extractor = createExtractor()
        val aiJson = """{"amount":1,"type":"EXPENSE","category":"其他","confidence":0.5}"""

        val requestSlot = slot<ChatCompletionRequest>()

        coEvery {
            service.chatCompletions(any(), any(), capture(requestSlot))
        } returns buildResponse(aiJson)

        extractor.extractFromOcr("OCR文字")

        val systemContent = requestSlot.captured.messages[0].content
        assertTrue(systemContent.contains("OCR"))
    }

    @Test
    fun should_instructAiToPreferIngredientLikeCategory_forProducePurchases() = runTest {
        extractor = createExtractor()
        val aiJson = """{"amount":12,"type":"EXPENSE","category":"餐饮","confidence":0.6}"""
        val requestSlot = slot<ChatCompletionRequest>()

        coEvery {
            service.chatCompletions(any(), any(), capture(requestSlot))
        } returns buildResponse(aiJson)

        extractor.extract("买空心菜12元", listOf("食材", "餐饮"))

        val systemContent = requestSlot.captured.messages[0].content
        assertTrue(systemContent.contains("购买蔬菜、水果、肉蛋奶、米面粮油"))
        assertTrue(systemContent.contains("优先选择这些分类，而不是笼统选择“餐饮”"))
    }

    @Test
    fun should_instructAiToPreferDrinkCategory_forTeaAndBeverageInputs() = runTest {
        extractor = createExtractor()
        val aiJson = """{"amount":88,"type":"EXPENSE","category":"饮料","confidence":0.7}"""
        val requestSlot = slot<ChatCompletionRequest>()

        coEvery {
            service.chatCompletions(any(), any(), capture(requestSlot))
        } returns buildResponse(aiJson)

        extractor.extract("茶叶88元", listOf("饮料", "餐饮"))

        val systemContent = requestSlot.captured.messages[0].content
        assertTrue(systemContent.contains("以及奶茶、咖啡、饮品消费都优先归到这些分类"))
    }

    @Test
    fun should_instructAiToSumSameLineMultipleItemAmounts_andAvoidDefaultHalfConfidence() = runTest {
        extractor = createExtractor()
        val aiJson = """{"amount":30,"type":"EXPENSE","category":"饮料","confidence":0.9}"""
        val requestSlot = slot<ChatCompletionRequest>()

        coEvery {
            service.chatCompletions(any(), any(), capture(requestSlot))
        } returns buildResponse(aiJson)

        extractor.extract("奶茶10咖啡20", listOf("饮料", "餐饮"))

        val systemContent = requestSlot.captured.messages[0].content
        assertTrue(systemContent.contains("同一条输入里出现多个商品+金额"))
        assertTrue(systemContent.contains("汇总为总金额"))
        assertTrue(systemContent.contains("避免机械地固定返回 0.5"))
    }

    @Test
    fun should_appendUserCustomPrompt_when_textPromptConfigured() = runTest {
        every { secureConfigStore.getTextPrompt() } returns "优先把空心菜归到食材，备注保留品牌"
        extractor = createExtractor()
        val aiJson = """{"amount":12,"type":"EXPENSE","category":"食材","confidence":0.8}"""
        val requestSlot = slot<ChatCompletionRequest>()

        coEvery {
            service.chatCompletions(any(), any(), capture(requestSlot))
        } returns buildResponse(aiJson)

        extractor.extract("买空心菜12元", listOf("食材", "餐饮"))

        val systemContent = requestSlot.captured.messages[0].content
        assertTrue(systemContent.contains("附加用户自定义规则"))
        assertTrue(systemContent.contains("高优先级"))
        assertTrue(systemContent.contains("优先把空心菜归到食材，备注保留品牌"))
    }

    // ── Error cases ──

    @Test
    fun should_returnFailure_when_configNotConfigured() = runTest {
        extractor = createExtractor(apiKey = "", endpoint = "")

        val result = extractor.extract("午饭20元")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun should_returnFailure_when_aiReturnsEmptyChoices() = runTest {
        extractor = createExtractor()

        coEvery {
            service.chatCompletions(any(), any(), any())
        } returns ChatCompletionResponse(id = "test", choices = emptyList())

        val result = extractor.extract("test input")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun should_returnFailure_when_serviceThrowsException() = runTest {
        extractor = createExtractor()

        coEvery {
            service.chatCompletions(any(), any(), any())
        } throws RuntimeException("Network error")

        val result = extractor.extract("test")

        assertTrue(result.isFailure)
        assertEquals("Network error", result.exceptionOrNull()?.message)
    }

    @Test
    fun should_returnFailure_when_aiReturnsInvalidJson() = runTest {
        extractor = createExtractor()

        coEvery {
            service.chatCompletions(any(), any(), any())
        } returns buildResponse("not valid json at all")

        val result = extractor.extract("test")

        assertTrue(result.isFailure)
    }

    @Test
    fun should_parseJsonWrappedInMarkdownCodeFence_when_aiAddsFormatting() = runTest {
        extractor = createExtractor()
        val aiJson = """
            ```json
            {"amount":30,"type":"EXPENSE","category":"饮料","confidence":0.88}
            ```
        """.trimIndent()

        coEvery {
            service.chatCompletions(any(), any(), any())
        } returns buildResponse(aiJson)

        val result = extractor.extract("奶茶10咖啡20")

        assertTrue(result.isSuccess)
        assertEquals(30.0, result.getOrThrow().amount)
        assertEquals(0.88f, result.getOrThrow().confidence)
    }

    @Test
    fun should_handlePartialJsonWithDefaults_when_optionalFieldsMissing() = runTest {
        extractor = createExtractor()
        // Only required fields: type has default "EXPENSE", category has default "其他", confidence has default 0.0
        val aiJson = """{"amount":null,"type":"EXPENSE","category":"其他","confidence":0.7}"""

        coEvery {
            service.chatCompletions(any(), any(), any())
        } returns buildResponse(aiJson)

        val result = extractor.extract("something")

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertNull(data.amount)
        assertNull(data.merchantName)
        assertEquals(todayStr, data.date)
    }
}
