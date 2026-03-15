package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.data.ai.ExtractionStrategyManager
import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.model.ExtractionSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AiExtractionRepositoryImplTest {

    private lateinit var strategyManager: ExtractionStrategyManager
    private lateinit var repository: AiExtractionRepositoryImpl

    private val sampleResult = ExtractionResult(
        amount = 50.0,
        type = "EXPENSE",
        category = "餐饮",
        date = "2026-03-15",
        note = "午饭",
        confidence = 0.9f,
        source = ExtractionSource.AZURE_AI
    )

    @BeforeEach
    fun setUp() {
        strategyManager = mockk()
        repository = AiExtractionRepositoryImpl(strategyManager)
    }

    @Test
    fun should_delegateToStrategyManager_when_extractCalled() = runTest {
        coEvery { strategyManager.extract("午饭50元") } returns Result.success(sampleResult)

        val result = repository.extract("午饭50元")

        assertTrue(result.isSuccess)
        assertEquals(50.0, result.getOrThrow().amount)
        coVerify(exactly = 1) { strategyManager.extract("午饭50元") }
    }

    @Test
    fun should_delegateToStrategyManager_when_extractOnlineCalled() = runTest {
        coEvery { strategyManager.extractOnline("test") } returns Result.success(sampleResult)

        val result = repository.extractOnline("test")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { strategyManager.extractOnline("test") }
    }

    @Test
    fun should_delegateToStrategyManager_when_extractFromOcrCalled() = runTest {
        coEvery { strategyManager.extractFromOcr("ocr text") } returns Result.success(sampleResult)

        val result = repository.extractFromOcr("ocr text")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { strategyManager.extractFromOcr("ocr text") }
    }

    @Test
    fun should_propagateFailure_when_strategyManagerFails() = runTest {
        coEvery {
            strategyManager.extract("bad input")
        } returns Result.failure(RuntimeException("extraction failed"))

        val result = repository.extract("bad input")

        assertTrue(result.isFailure)
        assertEquals("extraction failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun should_propagateFailure_when_extractOnlineFails() = runTest {
        coEvery {
            strategyManager.extractOnline("test")
        } returns Result.failure(RuntimeException("network error"))

        val result = repository.extractOnline("test")

        assertTrue(result.isFailure)
    }

    @Test
    fun should_propagateFailure_when_extractFromOcrFails() = runTest {
        coEvery {
            strategyManager.extractFromOcr("ocr")
        } returns Result.failure(RuntimeException("ocr error"))

        val result = repository.extractFromOcr("ocr")

        assertTrue(result.isFailure)
    }
}
