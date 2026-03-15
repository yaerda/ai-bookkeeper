package com.aibookkeeper.feature.input.quick

import app.cash.turbine.test
import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.model.ExtractionSource
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.repository.AiExtractionRepository
import com.aibookkeeper.core.data.repository.CategoryRepository
import com.aibookkeeper.core.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class QuickInputViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val aiExtractionRepository: AiExtractionRepository = mockk()
    private val transactionRepository: TransactionRepository = mockk()
    private val categoryRepository: CategoryRepository = mockk()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): QuickInputViewModel {
        return QuickInputViewModel(aiExtractionRepository, transactionRepository, categoryRepository)
    }

    private fun createExtractionResult(
        amount: Double? = 35.0,
        type: String = "EXPENSE",
        category: String = "餐饮",
        confidence: Float = 0.92f
    ) = ExtractionResult(
        amount = amount,
        type = type,
        category = category,
        date = LocalDate.now().toString(),
        note = "午饭",
        confidence = confidence,
        source = ExtractionSource.LOCAL_RULE
    )

    // ── Initial state ────────────────────────────────────────────────────

    @Nested
    inner class InitialState {

        @Test
        fun should_beIdle_when_initialized() {
            val vm = createViewModel()
            val state = vm.uiState.value
            assertTrue(state is QuickInputUiState.Idle)
            assertNull((state as QuickInputUiState.Idle).preselectedCategory)
        }
    }

    // ── setPreselectedCategory ───────────────────────────────────────────

    @Nested
    inner class SetPreselectedCategory {

        @Test
        fun should_setCategory_when_preselectedCategoryCalled() {
            val vm = createViewModel()

            vm.setPreselectedCategory("餐饮", "🍚")

            val state = vm.uiState.value as QuickInputUiState.Idle
            assertEquals("餐饮", state.preselectedCategory)
            assertEquals("🍚", state.preselectedCategoryIcon)
        }

        @Test
        fun should_clearCategory_when_nullPassed() {
            val vm = createViewModel()

            vm.setPreselectedCategory("餐饮", "🍚")
            vm.setPreselectedCategory(null, null)

            val state = vm.uiState.value as QuickInputUiState.Idle
            assertNull(state.preselectedCategory)
        }
    }

    // ── submitText ───────────────────────────────────────────────────────

    @Nested
    inner class SubmitText {

        @Test
        fun should_showError_when_blankInput() {
            val vm = createViewModel()
            vm.submitText("")

            val state = vm.uiState.value
            assertTrue(state is QuickInputUiState.Error)
            assertEquals("请输入记账内容", (state as QuickInputUiState.Error).message)
        }

        @Test
        fun should_showPreview_when_extractionSucceeds() = runTest {
            coEvery { aiExtractionRepository.extract("午饭35") } returns
                    Result.success(createExtractionResult())

            val vm = createViewModel()
            vm.submitText("午饭35")
            testDispatcher.scheduler.advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state is QuickInputUiState.Preview)
            assertEquals(35.0, (state as QuickInputUiState.Preview).amount)
            assertEquals("餐饮", state.category)
        }

        @Test
        fun should_showError_when_extractionFails() = runTest {
            coEvery { aiExtractionRepository.extract(any()) } returns
                    Result.failure(RuntimeException("超时"))

            val vm = createViewModel()
            vm.submitText("午饭35")
            testDispatcher.scheduler.advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state is QuickInputUiState.Error)
            assertEquals("超时", (state as QuickInputUiState.Error).message)
        }
    }

    // ── submitCategoryAmount ─────────────────────────────────────────────

    @Nested
    inner class SubmitCategoryAmount {

        @Test
        fun should_showSuccess_when_categoryAmountSaved() = runTest {
            coEvery { categoryRepository.findByNameAndType(any(), any()) } returns null
            coEvery { transactionRepository.create(any()) } returns Result.success(1L)

            val vm = createViewModel()
            vm.submitCategoryAmount(35.0, "餐饮")
            testDispatcher.scheduler.advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state is QuickInputUiState.Success)
            assertEquals(35.0, (state as QuickInputUiState.Success).amount)
            assertEquals("餐饮", state.category)
        }

        @Test
        fun should_useNotificationQuickSource_when_categoryAmountSaved() = runTest {
            coEvery { categoryRepository.findByNameAndType(any(), any()) } returns null
            coEvery { transactionRepository.create(any()) } returns Result.success(1L)

            val vm = createViewModel()
            vm.submitCategoryAmount(35.0, "餐饮")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                transactionRepository.create(match {
                    it.source == TransactionSource.NOTIFICATION_QUICK
                })
            }
        }

        @Test
        fun should_showError_when_categoryAmountSaveFails() = runTest {
            coEvery { categoryRepository.findByNameAndType(any(), any()) } returns null
            coEvery { transactionRepository.create(any()) } returns
                    Result.failure(RuntimeException("DB error"))

            val vm = createViewModel()
            vm.submitCategoryAmount(35.0, "餐饮")
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(vm.uiState.value is QuickInputUiState.Error)
        }
    }

    // ── confirmSave ──────────────────────────────────────────────────────

    @Nested
    inner class ConfirmSave {

        @Test
        fun should_doNothing_when_notInPreviewState() {
            val vm = createViewModel()
            vm.confirmSave()

            assertTrue(vm.uiState.value is QuickInputUiState.Idle)
        }

        @Test
        fun should_saveTransaction_when_inPreviewState() = runTest {
            coEvery { aiExtractionRepository.extract("午饭35") } returns
                    Result.success(createExtractionResult())
            coEvery { categoryRepository.findByNameAndType(any(), any()) } returns null
            coEvery { transactionRepository.create(any()) } returns Result.success(1L)

            val vm = createViewModel()
            vm.submitText("午饭35")
            testDispatcher.scheduler.advanceUntilIdle()

            vm.confirmSave()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state is QuickInputUiState.Success)
        }

        @Test
        fun should_detectIncomeType_when_extractionTypeIsIncome() = runTest {
            val extraction = createExtractionResult(
                amount = 8000.0,
                type = "INCOME",
                category = "工资"
            )
            coEvery { aiExtractionRepository.extract("工资8000") } returns Result.success(extraction)
            coEvery { categoryRepository.findByNameAndType(any(), any()) } returns null
            coEvery { transactionRepository.create(any()) } returns Result.success(1L)

            val vm = createViewModel()
            vm.submitText("工资8000")
            testDispatcher.scheduler.advanceUntilIdle()

            vm.confirmSave()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                transactionRepository.create(match {
                    it.type == TransactionType.INCOME
                })
            }
        }
    }

    // ── resetToIdle ──────────────────────────────────────────────────────

    @Nested
    inner class ResetToIdle {

        @Test
        fun should_returnToIdle_when_resetCalled() {
            val vm = createViewModel()
            vm.submitText("")
            assertTrue(vm.uiState.value is QuickInputUiState.Error)

            vm.resetToIdle()

            assertTrue(vm.uiState.value is QuickInputUiState.Idle)
        }

        @Test
        fun should_clearPreselectedCategory_when_resetCalled() {
            val vm = createViewModel()
            vm.setPreselectedCategory("餐饮", "🍚")

            vm.resetToIdle()

            val state = vm.uiState.value as QuickInputUiState.Idle
            assertNull(state.preselectedCategory)
        }
    }
}
