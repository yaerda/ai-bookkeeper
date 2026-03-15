package com.aibookkeeper.feature.input.bills

import app.cash.turbine.test
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.SyncStatus
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
import java.time.LocalDateTime
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class BillsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val transactionRepository: TransactionRepository = mockk()

    private val now = LocalDateTime.now()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createTransaction(
        id: Long = 1,
        amount: Double = 10.0,
        type: TransactionType = TransactionType.EXPENSE,
        date: LocalDateTime = now
    ) = Transaction(
        id = id,
        amount = amount,
        type = type,
        categoryId = 1,
        categoryName = "餐饮",
        date = date,
        createdAt = now,
        updatedAt = now,
        source = TransactionSource.MANUAL,
        status = TransactionStatus.CONFIRMED,
        syncStatus = SyncStatus.LOCAL
    )

    private fun createViewModel(
        transactions: List<Transaction> = emptyList(),
        expense: Double = 0.0,
        income: Double = 0.0
    ): BillsViewModel {
        every { transactionRepository.observeByMonth(any()) } returns flowOf(transactions)
        every { transactionRepository.observeMonthlyExpense(any()) } returns flowOf(expense)
        every { transactionRepository.observeMonthlyIncome(any()) } returns flowOf(income)

        return BillsViewModel(transactionRepository)
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
    }

    // ── Data loading ─────────────────────────────────────────────────────

    @Nested
    inner class DataLoading {

        @Test
        fun should_showMonthlySummary_when_dataLoaded() = runTest {
            val vm = createViewModel(expense = 2500.0, income = 8000.0)

            vm.uiState.test {
                awaitItem() // initial
                val loaded = awaitItem()
                assertEquals(2500.0, loaded.monthExpense)
                assertEquals(8000.0, loaded.monthIncome)
                assertFalse(loaded.isLoading)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_groupByDay_when_transactionsLoaded() = runTest {
            val today = LocalDate.now()
            val yesterday = today.minusDays(1)
            val tx1 = createTransaction(id = 1, amount = 35.0, date = today.atTime(12, 0))
            val tx2 = createTransaction(id = 2, amount = 20.0, date = today.atTime(8, 0))
            val tx3 = createTransaction(id = 3, amount = 50.0, date = yesterday.atTime(19, 0))

            val vm = createViewModel(transactions = listOf(tx1, tx2, tx3))

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(2, loaded.dayGroups.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_labelToday_when_groupDateIsToday() = runTest {
            val tx = createTransaction(date = LocalDate.now().atTime(12, 0))
            val vm = createViewModel(transactions = listOf(tx))

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals("今天", loaded.dayGroups.first().label)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_labelYesterday_when_groupDateIsYesterday() = runTest {
            val tx = createTransaction(date = LocalDate.now().minusDays(1).atTime(12, 0))
            val vm = createViewModel(transactions = listOf(tx))

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals("昨天", loaded.dayGroups.first().label)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_calculateDayExpense_when_groupHasExpenses() = runTest {
            val today = LocalDate.now()
            val tx1 = createTransaction(id = 1, amount = 35.0, date = today.atTime(12, 0))
            val tx2 = createTransaction(id = 2, amount = 20.0, date = today.atTime(8, 0))
            val vm = createViewModel(transactions = listOf(tx1, tx2))

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(55.0, loaded.dayGroups.first().dayExpense)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_calculateDayIncome_when_groupHasIncome() = runTest {
            val today = LocalDate.now()
            val tx = createTransaction(id = 1, amount = 5000.0, type = TransactionType.INCOME, date = today.atTime(9, 0))
            val vm = createViewModel(transactions = listOf(tx))

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(5000.0, loaded.dayGroups.first().dayIncome)
                assertEquals(0.0, loaded.dayGroups.first().dayExpense)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_showEmptyDayGroups_when_noTransactions() = runTest {
            val vm = createViewModel()

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertTrue(loaded.dayGroups.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_sortDayGroupsDescending_when_multipleGroups() = runTest {
            val today = LocalDate.now()
            val twoDaysAgo = today.minusDays(2)
            val tx1 = createTransaction(id = 1, date = twoDaysAgo.atTime(12, 0))
            val tx2 = createTransaction(id = 2, date = today.atTime(12, 0))
            val vm = createViewModel(transactions = listOf(tx1, tx2))

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertTrue(loaded.dayGroups[0].date > loaded.dayGroups[1].date)
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
            val currentMonth = YearMonth.now()

            vm.uiState.test {
                awaitItem() // initial loading state
                awaitItem() // loaded state

                vm.previousMonth()

                val state = awaitItem()
                assertEquals(currentMonth.minusMonths(1), state.currentMonth)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_goToNextMonth_when_nextMonthCalled() = runTest {
            val vm = createViewModel()

            vm.uiState.test {
                awaitItem() // initial
                awaitItem() // loaded

                vm.previousMonth()
                awaitItem() // month - 1

                vm.nextMonth()
                val state = awaitItem()
                assertEquals(YearMonth.now(), state.currentMonth)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    // ── Delete transaction ───────────────────────────────────────────────

    @Nested
    inner class DeleteTransaction {

        @Test
        fun should_callRepositoryDelete_when_deleteTransactionCalled() = runTest {
            coEvery { transactionRepository.delete(any()) } returns Result.success(Unit)
            val vm = createViewModel()

            vm.deleteTransaction(42L)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { transactionRepository.delete(42L) }
        }
    }
}
