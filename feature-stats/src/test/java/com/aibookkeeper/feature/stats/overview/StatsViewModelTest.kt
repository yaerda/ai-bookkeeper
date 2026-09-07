package com.aibookkeeper.feature.stats.overview

import app.cash.turbine.test
import com.aibookkeeper.core.data.model.CategoryExpense
import com.aibookkeeper.core.data.model.ProjectBinding
import com.aibookkeeper.core.data.model.ProjectLedgerState
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.repository.ProjectRepository
import com.aibookkeeper.core.data.repository.LedgerContext
import com.aibookkeeper.core.data.repository.LedgerContextState
import com.aibookkeeper.core.data.repository.TransactionRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val projectRepository: ProjectRepository = mockk()
    private val ledgerContext: LedgerContext = mockk()
    private val ledgerState = MutableStateFlow(LedgerContextState())

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { projectRepository.currentLedgerState } returns MutableStateFlow(ProjectLedgerState())
        every { ledgerContext.state } returns ledgerState
        every { transactionRepository.observeExpenseBreakdown(any()) } returns flowOf(emptyList())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        transactions: List<Transaction> = emptyList()
    ): StatsViewModel {
        every { transactionRepository.observeByMonth(any()) } returns flowOf(transactions)

        return StatsViewModel(transactionRepository, projectRepository, ledgerContext)
    }

    private fun transaction(
        amount: Double,
        type: TransactionType,
        categoryId: Long? = if (type == TransactionType.EXPENSE) 1L else 2L,
        categoryName: String? = if (type == TransactionType.EXPENSE) "餐饮" else "工资",
        projectIds: List<String>? = null
    ) = Transaction(
        id = amount.toLong(),
        amount = amount,
        type = type,
        categoryId = categoryId,
        categoryName = categoryName,
        categoryColor = if (type == TransactionType.EXPENSE) "#FF5722" else "#4CAF50",
        date = java.time.LocalDateTime.of(2026, 9, 8, 9, 0),
        createdAt = java.time.LocalDateTime.of(2026, 9, 8, 9, 0),
        updatedAt = java.time.LocalDateTime.of(2026, 9, 8, 9, 0),
        source = TransactionSource.MANUAL,
        status = TransactionStatus.CONFIRMED,
        syncStatus = SyncStatus.SYNCED,
        projectIds = projectIds
    )

    @Test
    fun `project statistics retain Room category labels and colors`() = runTest {
        every { transactionRepository.observeExpenseBreakdown(any()) } returns flowOf(
            listOf(CategoryExpense(1, "本地餐饮", "#123456", 30.0, 1f))
        )
        every { projectRepository.currentLedgerState } returns MutableStateFlow(
            ProjectLedgerState(
                ledgerId = ledgerState.value.selectedLedgerId,
                projects = listOf(ProjectBinding("p1", ledgerState.value.selectedLedgerId, "旅行", true, null, null, "Asia/Shanghai", 1, true, true))
            )
        )
        val vm = createViewModel(listOf(
            transaction(10.0, TransactionType.EXPENSE, categoryName = null, projectIds = listOf("p1")).copy(categoryColor = null),
            transaction(20.0, TransactionType.EXPENSE, categoryName = null)
        ))
        vm.uiState.test {
            awaitItem()
            val unfiltered = awaitItem()
            assertEquals("本地餐饮", unfiltered.categoryBreakdown.single().categoryName)
            vm.selectProject("p1")
            val filtered = awaitItem()
            assertEquals(10.0, filtered.monthExpense)
            assertEquals("本地餐饮", filtered.categoryBreakdown.single().categoryName)
            assertEquals("#123456", filtered.categoryBreakdown.single().categoryColor)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uncatalogued shared categories use distinct repository-compatible keys`() = runTest {
        val vm = createViewModel(listOf(
            transaction(10.0, TransactionType.EXPENSE, categoryId = null, categoryName = "Alpha"),
            transaction(20.0, TransactionType.EXPENSE, categoryId = null, categoryName = "Beta")
        ))
        vm.uiState.test {
            awaitItem()
            val loaded = awaitItem()
            assertEquals(2, loaded.categoryBreakdown.map { it.categoryId }.distinct().size)
            assertEquals(setOf(-"Alpha".hashCode().toLong(), -"Beta".hashCode().toLong()), loaded.categoryBreakdown.map { it.categoryId }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ledger change clears unavailable project filter before new projects load`() = runTest {
        every { projectRepository.currentLedgerState } returns MutableStateFlow(
            ProjectLedgerState(
                ledgerId = ledgerState.value.selectedLedgerId,
                projects = listOf(ProjectBinding("p1", ledgerState.value.selectedLedgerId, "旅行", true, null, null, "Asia/Shanghai", 1, true, true))
            )
        )
        val vm = createViewModel(listOf(
            transaction(10.0, TransactionType.EXPENSE, projectIds = listOf("p1")),
            transaction(20.0, TransactionType.EXPENSE)
        ))
        vm.uiState.test {
            awaitItem()
            awaitItem()
            vm.selectProject("p1")
            assertEquals(10.0, awaitItem().monthExpense)
            ledgerState.value = ledgerState.value.copy(selectedLedgerId = "other", selectionVersion = 1)
            val changed = awaitItem()
            assertNull(changed.selectedProjectId)
            assertTrue(changed.availableProjects.isEmpty())
            assertEquals(30.0, changed.monthExpense)
            cancelAndIgnoreRemainingEvents()
        }
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
            val vm = createViewModel(
                transactions = listOf(
                    transaction(3000.0, TransactionType.EXPENSE),
                    transaction(8000.0, TransactionType.INCOME)
                )
            )

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
            val vm = createViewModel(
                transactions = listOf(
                    transaction(3000.0, TransactionType.EXPENSE),
                    transaction(8000.0, TransactionType.INCOME)
                )
            )

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(5000.0, loaded.balance)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_showNegativeBalance_when_expenseExceedsIncome() = runTest {
            val vm = createViewModel(
                transactions = listOf(
                    transaction(10000.0, TransactionType.EXPENSE),
                    transaction(5000.0, TransactionType.INCOME)
                )
            )

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(-5000.0, loaded.balance)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_showCategoryBreakdown_when_dataLoaded() = runTest {
            val vm = createViewModel(
                transactions = listOf(
                    transaction(1200.0, TransactionType.EXPENSE, 1L, "餐饮"),
                    transaction(600.0, TransactionType.EXPENSE, 2L, "交通"),
                    transaction(1200.0, TransactionType.EXPENSE, 3L, "购物")
                )
            )

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(3, loaded.categoryBreakdown.size)
                assertEquals("餐饮", loaded.categoryBreakdown[0].categoryName)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_filterBySelectedProject_withoutDoubleCountingMultiTagTransactions() = runTest {
            every { projectRepository.currentLedgerState } returns MutableStateFlow(
                ProjectLedgerState(
                    ledgerId = ledgerState.value.selectedLedgerId,
                    projects = listOf(
                        com.aibookkeeper.core.data.model.ProjectBinding(
                            projectId = "p1",
                            ledgerId = "l1",
                            name = "装修",
                            enabled = true,
                            startDate = null,
                            endDate = null,
                            timeZone = "Asia/Shanghai",
                            version = 1,
                            active = true,
                            canEdit = true
                        )
                    )
                )
            )
            val vm = createViewModel(
                transactions = listOf(
                    transaction(100.0, TransactionType.EXPENSE, 1L, "餐饮", listOf("p1", "p2")),
                    transaction(50.0, TransactionType.EXPENSE, 2L, "交通", listOf("p1")),
                    transaction(25.0, TransactionType.EXPENSE, 3L, "购物", listOf("p2"))
                )
            )

            vm.uiState.test {
                awaitItem()
                awaitItem()
                vm.selectProject("p1")
                val filtered = awaitItem()
                assertEquals(150.0, filtered.monthExpense)
                assertEquals(2, filtered.categoryBreakdown.size)
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
