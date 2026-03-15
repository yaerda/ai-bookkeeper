package com.aibookkeeper.feature.input.home

import app.cash.turbine.test
import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.repository.AiExtractionRepository
import com.aibookkeeper.core.data.repository.CategoryRepository
import com.aibookkeeper.core.data.repository.TransactionRepository
import com.aibookkeeper.core.data.repository.VoiceTranscriptionRepository
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
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val transactionRepository: TransactionRepository = mockk()
    private val categoryRepository: CategoryRepository = mockk()
    private val aiExtractionRepository: AiExtractionRepository = mockk(relaxed = true)
    private val voiceTranscriptionRepository: VoiceTranscriptionRepository = mockk(relaxed = true)

    private val now = LocalDateTime.now()
    private val currentMonth = YearMonth.now()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        monthExpense: Double = 0.0,
        monthIncome: Double = 0.0,
        transactions: List<Transaction> = emptyList(),
        categories: List<Category> = emptyList()
    ): HomeViewModel {
        every { transactionRepository.observeMonthlyExpense(any()) } returns flowOf(monthExpense)
        every { transactionRepository.observeMonthlyIncome(any()) } returns flowOf(monthIncome)
        every { transactionRepository.observeByMonth(any()) } returns flowOf(transactions)
        every { categoryRepository.observeExpenseCategories() } returns flowOf(categories)

        return HomeViewModel(
            transactionRepository,
            categoryRepository,
            aiExtractionRepository,
            voiceTranscriptionRepository
        )
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
        categoryIcon = "ic_food",
        categoryColor = "#FF5722",
        date = date,
        createdAt = now,
        updatedAt = now,
        source = TransactionSource.MANUAL,
        status = TransactionStatus.CONFIRMED,
        syncStatus = SyncStatus.LOCAL
    )

    private fun createCategory(
        id: Long = 1,
        name: String = "餐饮",
        icon: String = "ic_food",
        color: String = "#FF5722"
    ) = Category(
        id = id,
        name = name,
        icon = icon,
        color = color,
        type = TransactionType.EXPENSE
    )

    // ── Initial state ────────────────────────────────────────────────────

    @Nested
    inner class InitialState {

        @Test
        fun should_showLoading_when_initialized() {
            val vm = createViewModel()
            val state = vm.uiState.value
            assertTrue(state.isLoading)
        }

        @Test
        fun should_haveZeroAmounts_when_initialized() {
            val vm = createViewModel()
            val state = vm.uiState.value
            assertEquals(0.0, state.todayExpense)
            assertEquals(0.0, state.monthExpense)
            assertEquals(0.0, state.monthIncome)
        }

        @Test
        fun should_haveEmptyLists_when_initialized() {
            val vm = createViewModel()
            val state = vm.uiState.value
            assertTrue(state.recentTransactions.isEmpty())
            assertTrue(state.expenseCategories.isEmpty())
        }
    }

    // ── Data loading ─────────────────────────────────────────────────────

    @Nested
    inner class DataLoading {

        @Test
        fun should_showMonthlyExpense_when_dataLoaded() = runTest {
            val vm = createViewModel(monthExpense = 1500.0)

            vm.uiState.test {
                // Skip initial loading state
                val initial = awaitItem()
                val loaded = awaitItem()
                assertEquals(1500.0, loaded.monthExpense)
                assertFalse(loaded.isLoading)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_showMonthlyIncome_when_dataLoaded() = runTest {
            val vm = createViewModel(monthIncome = 8000.0)

            vm.uiState.test {
                awaitItem() // initial
                val loaded = awaitItem()
                assertEquals(8000.0, loaded.monthIncome)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_calculateTodayExpense_when_transactionsLoaded() = runTest {
            val todayTx1 = createTransaction(id = 1, amount = 35.0, date = now)
            val todayTx2 = createTransaction(id = 2, amount = 20.0, date = now)
            val vm = createViewModel(transactions = listOf(todayTx1, todayTx2))

            vm.uiState.test {
                awaitItem() // initial
                val loaded = awaitItem()
                assertEquals(55.0, loaded.todayExpense)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_excludeIncomeFromTodayExpense_when_mixedTransactions() = runTest {
            val expense = createTransaction(id = 1, amount = 35.0, type = TransactionType.EXPENSE)
            val income = createTransaction(id = 2, amount = 100.0, type = TransactionType.INCOME)
            val vm = createViewModel(transactions = listOf(expense, income))

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(35.0, loaded.todayExpense)
                assertEquals(100.0, loaded.todayIncome)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_limitRecentTransactions_when_manyExist() = runTest {
            val transactions = (1..30L).map { createTransaction(id = it) }
            val vm = createViewModel(transactions = transactions)

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(20, loaded.recentTransactions.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_showCategories_when_loaded() = runTest {
            val categories = listOf(
                createCategory(1, "餐饮"),
                createCategory(2, "交通", "ic_transport", "#2196F3")
            )
            val vm = createViewModel(categories = categories)

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(2, loaded.expenseCategories.size)
                assertEquals("餐饮", loaded.expenseCategories[0].name)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    // ── Edge cases ───────────────────────────────────────────────────────

    @Nested
    inner class EdgeCases {

        @Test
        fun should_showZeroTodayExpense_when_noTodayTransactions() = runTest {
            val yesterday = now.minusDays(1)
            val tx = createTransaction(date = yesterday)
            val vm = createViewModel(transactions = listOf(tx))

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(0.0, loaded.todayExpense)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_setCurrentMonth_when_loaded() = runTest {
            val vm = createViewModel()

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(currentMonth, loaded.currentMonth)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_calculateTodayIncome_when_incomeTransactionsExist() = runTest {
            val incomeTx = createTransaction(id = 1, amount = 5000.0, type = TransactionType.INCOME, date = now)
            val vm = createViewModel(transactions = listOf(incomeTx))

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(5000.0, loaded.todayIncome)
                assertEquals(0.0, loaded.todayExpense)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_returnExactly20_when_exactly20TransactionsExist() = runTest {
            val transactions = (1..20L).map { createTransaction(id = it) }
            val vm = createViewModel(transactions = transactions)

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(20, loaded.recentTransactions.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_countOnlyTodayTransactions_when_mixedDatesExist() = runTest {
            val todayTx = createTransaction(id = 1, amount = 30.0, date = now)
            val yesterdayTx = createTransaction(id = 2, amount = 100.0, date = now.minusDays(1))
            val vm = createViewModel(transactions = listOf(todayTx, yesterdayTx))

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(30.0, loaded.todayExpense)
                assertEquals(2, loaded.recentTransactions.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_setCurrentMonthInInitialState_when_created() {
            val vm = createViewModel()
            assertEquals(currentMonth, vm.uiState.value.currentMonth)
        }
    }

    // ── Category grid for navigation ─────────────────────────────────────

    @Nested
    inner class CategoryGridNavigation {

        @Test
        fun should_exposeCategoryIds_when_categoriesLoaded() = runTest {
            val categories = listOf(
                createCategory(id = 10, name = "餐饮"),
                createCategory(id = 20, name = "交通", icon = "ic_transport", color = "#2196F3"),
                createCategory(id = 30, name = "购物", icon = "ic_shopping", color = "#9C27B0")
            )
            val vm = createViewModel(categories = categories)

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(3, loaded.expenseCategories.size)
                assertEquals(10L, loaded.expenseCategories[0].id)
                assertEquals(20L, loaded.expenseCategories[1].id)
                assertEquals(30L, loaded.expenseCategories[2].id)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_preserveCategoryProperties_when_loaded() = runTest {
            val categories = listOf(
                createCategory(id = 1, name = "餐饮", icon = "ic_food", color = "#FF5722")
            )
            val vm = createViewModel(categories = categories)

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                val cat = loaded.expenseCategories[0]
                assertEquals(1L, cat.id)
                assertEquals("餐饮", cat.name)
                assertEquals("ic_food", cat.icon)
                assertEquals("#FF5722", cat.color)
                assertEquals(TransactionType.EXPENSE, cat.type)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_returnEmptyCategories_when_noExpenseCategoriesExist() = runTest {
            val vm = createViewModel(categories = emptyList())

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertTrue(loaded.expenseCategories.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_loadManyCategories_when_moreThan8Exist() = runTest {
            val categories = (1..12L).map { i ->
                createCategory(id = i, name = "分类$i")
            }
            val vm = createViewModel(categories = categories)

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                // ViewModel exposes all categories; UI layer does take(8)
                assertEquals(12, loaded.expenseCategories.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_combineCategoriersWithTransactions_when_bothExist() = runTest {
            val categories = listOf(
                createCategory(id = 1, name = "餐饮"),
                createCategory(id = 2, name = "交通", icon = "ic_transport", color = "#2196F3")
            )
            val transactions = listOf(
                createTransaction(id = 1, amount = 35.0),
                createTransaction(id = 2, amount = 20.0)
            )
            val vm = createViewModel(
                categories = categories,
                transactions = transactions,
                monthExpense = 55.0
            )

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem()
                assertEquals(2, loaded.expenseCategories.size)
                assertEquals(2, loaded.recentTransactions.size)
                assertEquals(55.0, loaded.monthExpense)
                assertFalse(loaded.isLoading)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }
}
