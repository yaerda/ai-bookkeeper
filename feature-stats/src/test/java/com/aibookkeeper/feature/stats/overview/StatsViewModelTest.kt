package com.aibookkeeper.feature.stats.overview

import app.cash.turbine.test
import com.aibookkeeper.core.data.model.CategoryExpense
import com.aibookkeeper.core.data.repository.TransactionRepository
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
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val transactionRepository: TransactionRepository = mockk()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        expense: Double = 0.0,
        income: Double = 0.0,
        breakdown: List<CategoryExpense> = emptyList()
    ): StatsViewModel {
        every { transactionRepository.observeMonthlyExpense(any()) } returns flowOf(expense)
        every { transactionRepository.observeMonthlyIncome(any()) } returns flowOf(income)
        every { transactionRepository.observeExpenseBreakdown(any()) } returns flowOf(breakdown)

        return StatsViewModel(transactionRepository)
    }

    // ── Initial state ────────────────────────────────────────────────────

    @Nested
    inner class InitialState {

        @Test
        fun should_showCurrentMonth_when_initialized() {
            val vm = createViewModel()
            assertEquals(YearMonth.now(), vm.uiState.value.currentMonth)
        }

        @Test
        fun should_showLoading_when_initialized() {
            val vm = createViewModel()
            assertTrue(vm.uiState.value.isLoading)
        }

        @Test
        fun should_haveZeroAmounts_when_initialized() {
            val vm = createViewModel()
            val state = vm.uiState.value
            assertEquals(0.0, state.monthExpense)
            assertEquals(0.0, state.monthIncome)
            assertEquals(0.0, state.balance)
        }
    }

    // ── Data loading ─────────────────────────────────────────────────────

    @Nested
    inner class DataLoading {

        @Test
        fun should_showExpenseAndIncome_when_dataLoaded() = runTest {
            val vm = createViewModel(expense = 3000.0, income = 8000.0)

            vm.uiState.test {
                awaitItem() // initial
                val loaded = awaitItem()
                assertEquals(3000.0, loaded.monthExpense)
                assertEquals(8000.0, loaded.monthIncome)
                assertFalse(loaded.isLoading)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_calculateBalance_when_dataLoaded() = runTest {
            val vm = createViewModel(expense = 3000.0, income = 8000.0)

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(5000.0, loaded.balance)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_showNegativeBalance_when_expenseExceedsIncome() = runTest {
            val vm = createViewModel(expense = 10000.0, income = 5000.0)

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(-5000.0, loaded.balance)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_showCategoryBreakdown_when_dataLoaded() = runTest {
            val breakdown = listOf(
                CategoryExpense(1, "餐饮", "#FF5722", 1200.0, 0.4f),
                CategoryExpense(2, "交通", "#2196F3", 600.0, 0.2f),
                CategoryExpense(3, "购物", "#4CAF50", 1200.0, 0.4f)
            )
            val vm = createViewModel(expense = 3000.0, breakdown = breakdown)

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(3, loaded.categoryBreakdown.size)
                assertEquals("餐饮", loaded.categoryBreakdown[0].categoryName)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_showEmptyBreakdown_when_noExpenses() = runTest {
            val vm = createViewModel()

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertTrue(loaded.categoryBreakdown.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    // ── Month navigation ─────────────────────────────────────────────────

    @Nested
    inner class MonthNavigation {

        @Test
        fun should_goToPreviousMonth_when_previousMonthCalled() = runTest {
            val vm = createViewModel()
            val current = YearMonth.now()

            vm.uiState.test {
                awaitItem() // initial loading state
                awaitItem() // loaded state with current month

                vm.previousMonth()

                val state = awaitItem()
                assertEquals(current.minusMonths(1), state.currentMonth)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_goToNextMonth_when_nextMonthCalledAfterPrevious() = runTest {
            val vm = createViewModel()
            val current = YearMonth.now()

            vm.uiState.test {
                awaitItem() // initial
                awaitItem() // loaded

                vm.previousMonth()
                // Wait for the month-1 state
                var state = awaitItem()
                assertEquals(current.minusMonths(1), state.currentMonth)

                vm.nextMonth()
                state = awaitItem()
                assertEquals(current, state.currentMonth)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_goBackMultipleMonths_when_previousCalledMultipleTimes() = runTest {
            val vm = createViewModel()
            val current = YearMonth.now()

            vm.uiState.test {
                awaitItem() // initial
                awaitItem() // loaded

                vm.previousMonth()
                awaitItem() // month - 1

                vm.previousMonth()
                awaitItem() // month - 2

                vm.previousMonth()
                val state = awaitItem() // month - 3
                assertEquals(current.minusMonths(3), state.currentMonth)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }
}
