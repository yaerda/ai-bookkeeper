package com.aibookkeeper.feature.input.text

import app.cash.turbine.test
import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.ExtractionResult
import com.aibookkeeper.core.data.model.ExtractionSource
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.repository.AiExtractionRepository
import com.aibookkeeper.core.data.repository.CategoryRepository
import com.aibookkeeper.core.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
class TextInputViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val aiExtractionRepository: AiExtractionRepository = mockk()
    private val transactionRepository: TransactionRepository = mockk()
    private val categoryRepository: CategoryRepository = mockk()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { categoryRepository.observeExpenseCategories() } returns flowOf(emptyList())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): TextInputViewModel {
        return TextInputViewModel(aiExtractionRepository, transactionRepository, categoryRepository)
    }

    private fun createExtractionResult(
        amount: Double? = 35.0,
        type: String = "EXPENSE",
        category: String = "餐饮",
        confidence: Float = 0.92f,
        note: String? = "午饭",
        merchantName: String? = null,
        date: String = LocalDate.now().toString()
    ) = ExtractionResult(
        amount = amount,
        type = type,
        category = category,
        merchantName = merchantName,
        date = date,
        note = note,
        confidence = confidence,
        source = ExtractionSource.LOCAL_RULE
    )

    // ── Initial state ────────────────────────────────────────────────────

    @Nested
    inner class InitialState {

        @Test
        fun should_beIdle_when_initialized() {
            val vm = createViewModel()
            assertEquals(TextInputUiState.Idle, vm.uiState.value)
        }

        @Test
        fun should_haveEmptyCategories_when_initialized() = runTest {
            val vm = createViewModel()
            vm.categories.test {
                assertEquals(emptyList<Category>(), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    // ── submitText ───────────────────────────────────────────────────────

    @Nested
    inner class SubmitText {

        @Test
        fun should_showError_when_blankInputSubmitted() {
            val vm = createViewModel()

            vm.submitText("")

            val state = vm.uiState.value
            assertTrue(state is TextInputUiState.Error)
            assertEquals("请输入记账内容", (state as TextInputUiState.Error).message)
        }

        @Test
        fun should_showError_when_whitespaceOnlyInputSubmitted() {
            val vm = createViewModel()

            vm.submitText("   ")

            assertTrue(vm.uiState.value is TextInputUiState.Error)
        }

        @Test
        fun should_showExtracting_when_validInputSubmitted() = runTest {
            coEvery { aiExtractionRepository.extract(any()) } returns
                    Result.success(createExtractionResult())

            val vm = createViewModel()

            vm.uiState.test {
                assertEquals(TextInputUiState.Idle, awaitItem())

                vm.submitText("午饭35")

                assertEquals(TextInputUiState.Extracting, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_showPreview_when_extractionSucceeds() = runTest {
            val extraction = createExtractionResult(
                amount = 35.0,
                category = "餐饮",
                note = "午饭",
                confidence = 0.92f
            )
            coEvery { aiExtractionRepository.extract("午饭35") } returns Result.success(extraction)

            val vm = createViewModel()
            vm.submitText("午饭35")
            testDispatcher.scheduler.advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state is TextInputUiState.Preview)
            val preview = state as TextInputUiState.Preview
            assertEquals(35.0, preview.amount)
            assertEquals("餐饮", preview.category)
            assertEquals("午饭", preview.note)
            assertEquals(0.92f, preview.confidence)
            assertEquals("午饭35", preview.originalInput)
        }

        @Test
        fun should_showError_when_extractionFails() = runTest {
            coEvery { aiExtractionRepository.extract(any()) } returns
                    Result.failure(RuntimeException("网络超时"))

            val vm = createViewModel()
            vm.submitText("午饭35")
            testDispatcher.scheduler.advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state is TextInputUiState.Error)
            assertEquals("网络超时", (state as TextInputUiState.Error).message)
        }

        @Test
        fun should_showDefaultError_when_extractionFailsWithNullMessage() = runTest {
            coEvery { aiExtractionRepository.extract(any()) } returns
                    Result.failure(RuntimeException())

            val vm = createViewModel()
            vm.submitText("午饭35")
            testDispatcher.scheduler.advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state is TextInputUiState.Error)
            assertEquals("AI 提取失败，请重试", (state as TextInputUiState.Error).message)
        }
    }

    // ── confirmSave ──────────────────────────────────────────────────────

    @Nested
    inner class ConfirmSave {

        @Test
        fun should_doNothing_when_notInPreviewState() {
            val vm = createViewModel()

            vm.confirmSave()

            assertEquals(TextInputUiState.Idle, vm.uiState.value)
        }

        @Test
        fun should_showSuccess_when_saveSucceeds() = runTest {
            val extraction = createExtractionResult()
            coEvery { aiExtractionRepository.extract("午饭35") } returns Result.success(extraction)
            coEvery { categoryRepository.findByNameAndType(any(), any()) } returns null
            coEvery { transactionRepository.create(any()) } returns Result.success(1L)

            val vm = createViewModel()
            vm.submitText("午饭35")
            testDispatcher.scheduler.advanceUntilIdle()

            vm.confirmSave()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state is TextInputUiState.Success)
            val success = state as TextInputUiState.Success
            assertEquals(1L, success.transactionId)
            assertEquals(35.0, success.amount)
            assertEquals("餐饮", success.category)
        }

        @Test
        fun should_showError_when_saveFails() = runTest {
            val extraction = createExtractionResult()
            coEvery { aiExtractionRepository.extract("午饭35") } returns Result.success(extraction)
            coEvery { categoryRepository.findByNameAndType(any(), any()) } returns null
            coEvery { transactionRepository.create(any()) } returns
                    Result.failure(RuntimeException("数据库错误"))

            val vm = createViewModel()
            vm.submitText("午饭35")
            testDispatcher.scheduler.advanceUntilIdle()

            vm.confirmSave()
            testDispatcher.scheduler.advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state is TextInputUiState.Error)
            assertEquals("数据库错误", (state as TextInputUiState.Error).message)
        }

        @Test
        fun should_useCategoryId_when_categoryFoundInDb() = runTest {
            val extraction = createExtractionResult()
            val category = Category(
                id = 5, name = "餐饮", icon = "ic_food", color = "#FF5722",
                type = TransactionType.EXPENSE
            )
            coEvery { aiExtractionRepository.extract("午饭35") } returns Result.success(extraction)
            coEvery { categoryRepository.findByNameAndType("餐饮", TransactionType.EXPENSE) } returns category
            coEvery { transactionRepository.create(any()) } returns Result.success(1L)

            val vm = createViewModel()
            vm.submitText("午饭35")
            testDispatcher.scheduler.advanceUntilIdle()

            vm.confirmSave()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                transactionRepository.create(match { it.categoryId == 5L })
            }
        }

        @Test
        fun should_setStatusPending_when_lowConfidence() = runTest {
            val extraction = createExtractionResult(confidence = 0.5f)
            coEvery { aiExtractionRepository.extract("午饭35") } returns Result.success(extraction)
            coEvery { categoryRepository.findByNameAndType(any(), any()) } returns null
            coEvery { transactionRepository.create(any()) } returns Result.success(1L)

            val vm = createViewModel()
            vm.submitText("午饭35")
            testDispatcher.scheduler.advanceUntilIdle()

            vm.confirmSave()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                transactionRepository.create(match {
                    it.status == com.aibookkeeper.core.data.model.TransactionStatus.PENDING
                })
            }
        }

        @Test
        fun should_setStatusConfirmed_when_highConfidence() = runTest {
            val extraction = createExtractionResult(confidence = 0.9f)
            coEvery { aiExtractionRepository.extract("午饭35") } returns Result.success(extraction)
            coEvery { categoryRepository.findByNameAndType(any(), any()) } returns null
            coEvery { transactionRepository.create(any()) } returns Result.success(1L)

            val vm = createViewModel()
            vm.submitText("午饭35")
            testDispatcher.scheduler.advanceUntilIdle()

            vm.confirmSave()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                transactionRepository.create(match {
                    it.status == com.aibookkeeper.core.data.model.TransactionStatus.CONFIRMED
                })
            }
        }
    }

    // ── saveManual ───────────────────────────────────────────────────────

    @Nested
    inner class SaveManual {

        @Test
        fun should_showSuccess_when_manualSaveSucceeds() = runTest {
            coEvery { transactionRepository.create(any()) } returns Result.success(2L)

            val vm = createViewModel()
            vm.saveManual(50.0, 1L, "餐饮", "午饭", TransactionType.EXPENSE)
            testDispatcher.scheduler.advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state is TextInputUiState.Success)
            assertEquals(50.0, (state as TextInputUiState.Success).amount)
        }

        @Test
        fun should_setSourceManual_when_manualSave() = runTest {
            coEvery { transactionRepository.create(any()) } returns Result.success(1L)

            val vm = createViewModel()
            vm.saveManual(50.0, 1L, "餐饮", null, TransactionType.EXPENSE)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                transactionRepository.create(match {
                    it.source == TransactionSource.MANUAL
                })
            }
        }

        @Test
        fun should_setIncome_when_incomeTypeProvided() = runTest {
            coEvery { transactionRepository.create(any()) } returns Result.success(1L)

            val vm = createViewModel()
            vm.saveManual(8000.0, null, "工资", null, TransactionType.INCOME)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                transactionRepository.create(match {
                    it.type == TransactionType.INCOME && it.amount == 8000.0
                })
            }
        }

        @Test
        fun should_showError_when_manualSaveFails() = runTest {
            coEvery { transactionRepository.create(any()) } returns
                    Result.failure(RuntimeException("保存失败"))

            val vm = createViewModel()
            vm.saveManual(50.0, 1L, "餐饮", null, TransactionType.EXPENSE)
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(vm.uiState.value is TextInputUiState.Error)
        }
    }

    // ── resetToIdle ──────────────────────────────────────────────────────

    @Nested
    inner class ResetToIdle {

        @Test
        fun should_returnToIdle_when_resetCalled() = runTest {
            coEvery { aiExtractionRepository.extract(any()) } returns
                    Result.success(createExtractionResult())

            val vm = createViewModel()
            vm.submitText("午饭35")
            testDispatcher.scheduler.advanceUntilIdle()
            assertTrue(vm.uiState.value is TextInputUiState.Preview)

            vm.resetToIdle()

            assertEquals(TextInputUiState.Idle, vm.uiState.value)
        }

        @Test
        fun should_returnToIdle_when_calledFromErrorState() {
            val vm = createViewModel()
            vm.submitText("")
            assertTrue(vm.uiState.value is TextInputUiState.Error)

            vm.resetToIdle()

            assertEquals(TextInputUiState.Idle, vm.uiState.value)
        }
    }
}
