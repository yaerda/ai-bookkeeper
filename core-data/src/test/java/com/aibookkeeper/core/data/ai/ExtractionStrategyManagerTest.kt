package com.aibookkeeper.core.data.ai

import android.util.Log
import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.model.ExtractionSource
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

class ExtractionStrategyManagerTest {

    private lateinit var localExtractor: LocalRuleExtractor
    private lateinit var onlineExtractor: AzureOpenAiExtractor
    private lateinit var manager: ExtractionStrategyManager

    private val onlineResult = ExtractionResult(
        amount = 35.5,
        type = "EXPENSE",
        category = "餐饮",
        merchantName = "星巴克",
        date = "2026-03-15",
        note = "咖啡",
        confidence = 0.95f,
        source = ExtractionSource.AZURE_AI
    )

    private val localResult = ExtractionResult(
        amount = 35.0,
        type = "EXPENSE",
        category = "餐饮",
        date = "2026-03-15",
        note = "星巴克咖啡35元",
        confidence = 0.5f,
        source = ExtractionSource.LOCAL_RULE
    )

    @BeforeEach
    fun setUp() {
        // Mock android.util.Log to avoid UnsatisfiedLinkError in JVM tests
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0

        localExtractor = mockk()
        onlineExtractor = mockk()
        manager = ExtractionStrategyManager(localExtractor, onlineExtractor)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    // ── extract(): online-first with fallback ──

    @Test
    fun should_returnOnlineResult_when_onlineSucceeds() = runTest {
        coEvery { onlineExtractor.extract("星巴克咖啡35元") } returns Result.success(onlineResult)

        val result = manager.extract("星巴克咖啡35元")

        assertTrue(result.isSuccess)
        assertEquals(ExtractionSource.AZURE_AI, result.getOrThrow().source)
        assertEquals(0.95f, result.getOrThrow().confidence)
        coVerify(exactly = 0) { localExtractor.extract(any()) }
    }

    @Test
    fun should_fallbackToLocal_when_onlineReturnsFailure() = runTest {
        coEvery {
            onlineExtractor.extract("午饭20元")
        } returns Result.failure(RuntimeException("API error"))
        coEvery {
            localExtractor.extract("午饭20元")
        } returns Result.success(localResult)

        val result = manager.extract("午饭20元")

        assertTrue(result.isSuccess)
        assertEquals(ExtractionSource.LOCAL_RULE, result.getOrThrow().source)
    }

    @Test
    fun should_fallbackToLocal_when_onlineThrowsException() = runTest {
        coEvery {
            onlineExtractor.extract("test")
        } throws RuntimeException("Network unreachable")
        coEvery {
            localExtractor.extract("test")
        } returns Result.success(localResult)

        val result = manager.extract("test")

        assertTrue(result.isSuccess)
        assertEquals(ExtractionSource.LOCAL_RULE, result.getOrThrow().source)
    }

    // ── extractOnline(): no fallback ──

    @Test
    fun should_returnOnlineResult_when_extractOnlineSucceeds() = runTest {
        coEvery {
            onlineExtractor.extract("test input")
        } returns Result.success(onlineResult)

        val result = manager.extractOnline("test input")

        assertTrue(result.isSuccess)
        assertEquals(ExtractionSource.AZURE_AI, result.getOrThrow().source)
    }

    @Test
    fun should_returnFailure_when_extractOnlineFails() = runTest {
        coEvery {
            onlineExtractor.extract("test")
        } returns Result.failure(RuntimeException("fail"))

        val result = manager.extractOnline("test")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { localExtractor.extract(any()) }
    }

    // ── extractFromOcr(): online-first with fallback ──

    @Test
    fun should_returnOnlineOcrResult_when_ocrOnlineSucceeds() = runTest {
        coEvery {
            onlineExtractor.extractFromOcr("OCR text")
        } returns Result.success(onlineResult)

        val result = manager.extractFromOcr("OCR text")

        assertTrue(result.isSuccess)
        assertEquals(ExtractionSource.AZURE_AI, result.getOrThrow().source)
        coVerify(exactly = 0) { localExtractor.extractFromOcr(any()) }
    }

    @Test
    fun should_fallbackToLocalOcr_when_onlineOcrFails() = runTest {
        coEvery {
            onlineExtractor.extractFromOcr("OCR text")
        } returns Result.failure(RuntimeException("timeout"))
        coEvery {
            localExtractor.extractFromOcr("OCR text")
        } returns Result.success(localResult)

        val result = manager.extractFromOcr("OCR text")

        assertTrue(result.isSuccess)
        assertEquals(ExtractionSource.LOCAL_RULE, result.getOrThrow().source)
    }

    @Test
    fun should_fallbackToLocalOcr_when_onlineOcrThrowsException() = runTest {
        coEvery {
            onlineExtractor.extractFromOcr("OCR text")
        } throws RuntimeException("crash")
        coEvery {
            localExtractor.extractFromOcr("OCR text")
        } returns Result.success(localResult)

        val result = manager.extractFromOcr("OCR text")

        assertTrue(result.isSuccess)
        assertEquals(ExtractionSource.LOCAL_RULE, result.getOrThrow().source)
    }

    // ── Verify delegation correctness ──

    @Test
    fun should_passInputToOnlineExtractor_when_extractCalled() = runTest {
        val input = "独特的输入文本123"
        coEvery { onlineExtractor.extract(input) } returns Result.success(onlineResult)

        manager.extract(input)

        coVerify { onlineExtractor.extract(input) }
    }

    @Test
    fun should_passOcrTextToOnlineExtractor_when_extractFromOcrCalled() = runTest {
        val ocrText = "OCR识别结果456"
        coEvery { onlineExtractor.extractFromOcr(ocrText) } returns Result.success(onlineResult)

        manager.extractFromOcr(ocrText)

        coVerify { onlineExtractor.extractFromOcr(ocrText) }
    }
}
