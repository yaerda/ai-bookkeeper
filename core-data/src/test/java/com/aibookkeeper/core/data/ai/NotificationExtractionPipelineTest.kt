package com.aibookkeeper.core.data.ai

import android.util.Log
import com.aibookkeeper.core.data.ai.ExtractionCategoryProvider
import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.local.entity.CategoryEntity
import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.model.ExtractionSource
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.repository.RawEventRepository
import com.aibookkeeper.core.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

class NotificationExtractionPipelineTest {

    private lateinit var strategyManager: ExtractionStrategyManager
    private lateinit var rawEventRepository: RawEventRepository
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var categoryDao: CategoryDao
    private lateinit var extractionCategoryProvider: ExtractionCategoryProvider
    private lateinit var pipeline: NotificationExtractionPipeline

    private val todayStr = LocalDate.now().toString()

    private val sampleExtraction = ExtractionResult(
        amount = 35.5,
        type = "EXPENSE",
        category = "餐饮",
        merchantName = "星巴克",
        date = todayStr,
        note = "咖啡",
        confidence = 0.95f,
        source = ExtractionSource.AZURE_AI
    )

    private val foodCategory = CategoryEntity(
        id = 1, name = "餐饮", icon = "ic_food",
        color = "#FF5722", type = "EXPENSE"
    )

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0

        strategyManager = mockk()
        rawEventRepository = mockk(relaxUnitFun = true)
        transactionRepository = mockk()
        categoryDao = mockk()
        extractionCategoryProvider = mockk()

        pipeline = NotificationExtractionPipeline(
            strategyManager, rawEventRepository, transactionRepository, categoryDao, extractionCategoryProvider
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun should_createTransaction_when_notificationProcessedSuccessfully() = runTest {
        coEvery { rawEventRepository.captureEvent("WECHAT_PAY", any()) } returns Result.success(1L)
        coEvery { extractionCategoryProvider.getCategoryNames(emptyList()) } returns listOf("水果", "餐饮")
        coEvery {
            strategyManager.extract(any(), listOf("水果", "餐饮"))
        } returns Result.success(sampleExtraction)
        coEvery { categoryDao.findByNameAndType("餐饮", "EXPENSE") } returns foodCategory
        coEvery { transactionRepository.create(any<Transaction>()) } returns Result.success(100L)

        val txId = pipeline.processNotification("WECHAT_PAY", "微信支付 星巴克咖啡35.5元")

        assertEquals(100L, txId)
        coVerify { strategyManager.extract("微信支付 星巴克咖啡35.5元", listOf("水果", "餐饮")) }
        coVerify { rawEventRepository.markExtracted(1L, 100L) }
    }

    @Test
    fun should_returnNegativeOne_when_eventIsDuplicate() = runTest {
        coEvery { rawEventRepository.captureEvent("ALIPAY", any()) } returns Result.success(-1L)

        val txId = pipeline.processNotification("ALIPAY", "duplicate content")

        assertEquals(-1L, txId)
        coVerify(exactly = 0) { strategyManager.extract(any(), any()) }
    }

    @Test
    fun should_markFailed_when_extractionFails() = runTest {
        coEvery { rawEventRepository.captureEvent("WECHAT_PAY", any()) } returns Result.success(2L)
        coEvery { extractionCategoryProvider.getCategoryNames(emptyList()) } returns emptyList()
        coEvery { strategyManager.extract(any(), emptyList()) } returns Result.failure(RuntimeException("AI error"))

        val txId = pipeline.processNotification("WECHAT_PAY", "some content")

        assertEquals(-1L, txId)
        coVerify { rawEventRepository.markFailed(2L, "AI error") }
    }

    @Test
    fun should_setStatusToPending_when_lowConfidence() = runTest {
        val lowConfResult = sampleExtraction.copy(confidence = 0.4f)
        coEvery { rawEventRepository.captureEvent("ALIPAY", any()) } returns Result.success(3L)
        coEvery { extractionCategoryProvider.getCategoryNames(emptyList()) } returns emptyList()
        coEvery { strategyManager.extract(any(), emptyList()) } returns Result.success(lowConfResult)
        coEvery { categoryDao.findByNameAndType("餐饮", "EXPENSE") } returns foodCategory
        coEvery { transactionRepository.create(any<Transaction>()) } answers {
            val tx = firstArg<Transaction>()
            assertEquals(com.aibookkeeper.core.data.model.TransactionStatus.PENDING, tx.status)
            Result.success(101L)
        }

        pipeline.processNotification("ALIPAY", "低置信度内容")

        coVerify { transactionRepository.create(any<Transaction>()) }
    }

    @Test
    fun should_setStatusToConfirmed_when_highConfidence() = runTest {
        coEvery { rawEventRepository.captureEvent("WECHAT_PAY", any()) } returns Result.success(4L)
        coEvery { extractionCategoryProvider.getCategoryNames(emptyList()) } returns emptyList()
        coEvery { strategyManager.extract(any(), emptyList()) } returns Result.success(sampleExtraction)
        coEvery { categoryDao.findByNameAndType("餐饮", "EXPENSE") } returns foodCategory
        coEvery { transactionRepository.create(any<Transaction>()) } answers {
            val tx = firstArg<Transaction>()
            assertEquals(com.aibookkeeper.core.data.model.TransactionStatus.CONFIRMED, tx.status)
            Result.success(102L)
        }

        pipeline.processNotification("WECHAT_PAY", "高置信度内容")
    }

    @Test
    fun should_retryFailedEvents_when_called() = runTest {
        val failedEvent = com.aibookkeeper.core.data.local.entity.RawEventEntity(
            id = 10, sourceApp = "WECHAT_PAY",
            rawContent = "星巴克35元", contentHash = "abc",
            capturedAt = System.currentTimeMillis(),
            status = "FAILED", retryCount = 1
        )
        coEvery { rawEventRepository.getRetryableEvents() } returns listOf(failedEvent)
        coEvery { extractionCategoryProvider.getCategoryNames(emptyList()) } returns listOf("水果", "餐饮")
        coEvery {
            strategyManager.extract("星巴克35元", listOf("水果", "餐饮"))
        } returns Result.success(sampleExtraction)
        coEvery { categoryDao.findByNameAndType("餐饮", "EXPENSE") } returns foodCategory
        coEvery { transactionRepository.create(any<Transaction>()) } returns Result.success(200L)

        pipeline.retryFailedEvents()

        coVerify { strategyManager.extract("星巴克35元", listOf("水果", "餐饮")) }
        coVerify { rawEventRepository.markExtracted(10L, 200L) }
    }
}
