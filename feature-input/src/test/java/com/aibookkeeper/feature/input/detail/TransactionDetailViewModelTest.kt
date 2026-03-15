package com.aibookkeeper.feature.input.detail

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
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
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionDetailViewModelTest {

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
        amount: Double = 35.0,
        type: TransactionType = TransactionType.EXPENSE,
        status: TransactionStatus = TransactionStatus.CONFIRMED,
        source: TransactionSource = TransactionSource.MANUAL,
        note: String? = "午饭",
        merchantName: String? = null,
        aiConfidence: Float? = null,
        originalInput: String? = null
    ) = Transaction(
        id = id,
        amount = amount,
        type = type,
        categoryId = 1,
        categoryName = "餐饮",
        categoryIcon = "ic_food",
        categoryColor = "#FF5722",
        merchantName = merchantName,
        note = note,
        originalInput = originalInput,
        date = now,
        createdAt = now,
        updatedAt = now,
        source = source,
        status = status,
        syncStatus = SyncStatus.LOCAL,
        aiConfidence = aiConfidence
    )

    private fun createSavedStateHandle(transactionId: Long = 1L): SavedStateHandle {
        return SavedStateHandle(mapOf("transactionId" to transactionId))
    }

    private fun createViewModel(
        transactionId: Long = 1L,
        transaction: Transaction? = createTransaction(id = transactionId)
    ): TransactionDetailViewModel {
        every { transactionRepository.observeById(transactionId) } returns flowOf(transaction)
        return TransactionDetailViewModel(createSavedStateHandle(transactionId), transactionRepository)
    }

    // ── Initial state ────────────────────────────────────────────────────

    @Nested
    inner class InitialState {

        @Test
        fun should_showLoading_when_initialized() {
            val vm = createViewModel()
            val state = vm.uiState.value
            assertTrue(state is DetailUiState.Loading)
        }
    }

    // ── Data loading ─────────────────────────────────────────────────────

    @Nested
    inner class DataLoading {

        @Test
        fun should_showLoaded_when_transactionExists() = runTest {
            val tx = createTransaction(id = 1, amount = 35.0)
            val vm = createViewModel(transactionId = 1L, transaction = tx)

            vm.uiState.test {
                awaitItem() // initial Loading
                val loaded = awaitItem()
                assertTrue(loaded is DetailUiState.Loaded)
                assertEquals(35.0, (loaded as DetailUiState.Loaded).transaction.amount)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_showNotFound_when_transactionDoesNotExist() = runTest {
            val vm = createViewModel(transactionId = 999L, transaction = null)

            vm.uiState.test {
                awaitItem() // Loading
                val state = awaitItem()
                assertTrue(state is DetailUiState.NotFound)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_showTransactionDetails_when_loaded() = runTest {
            val tx = createTransaction(
                id = 5,
                amount = 88.5,
                type = TransactionType.EXPENSE,
                note = "晚餐",
                merchantName = "海底捞",
                source = TransactionSource.TEXT_AI,
                aiConfidence = 0.95f,
                originalInput = "海底捞晚餐88.5"
            )
            val vm = createViewModel(transactionId = 5L, transaction = tx)

            vm.uiState.test {
                awaitItem() // Loading
                val loaded = awaitItem() as DetailUiState.Loaded
                assertEquals(88.5, loaded.transaction.amount)
                assertEquals("晚餐", loaded.transaction.note)
                assertEquals("海底捞", loaded.transaction.merchantName)
                assertEquals(TransactionSource.TEXT_AI, loaded.transaction.source)
                assertEquals(0.95f, loaded.transaction.aiConfidence)
                assertEquals("海底捞晚餐88.5", loaded.transaction.originalInput)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_showIncomeTransaction_when_incomeTypeLoaded() = runTest {
            val tx = createTransaction(
                id = 10,
                amount = 8000.0,
                type = TransactionType.INCOME
            )
            val vm = createViewModel(transactionId = 10L, transaction = tx)

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem() as DetailUiState.Loaded
                assertEquals(TransactionType.INCOME, loaded.transaction.type)
                assertEquals(8000.0, loaded.transaction.amount)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_showPendingStatus_when_transactionPending() = runTest {
            val tx = createTransaction(
                id = 3,
                status = TransactionStatus.PENDING,
                aiConfidence = 0.5f
            )
            val vm = createViewModel(transactionId = 3L, transaction = tx)

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem() as DetailUiState.Loaded
                assertEquals(TransactionStatus.PENDING, loaded.transaction.status)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    // ── Reactive updates ─────────────────────────────────────────────────

    @Nested
    inner class ReactiveUpdates {

        @Test
        fun should_updateUi_when_transactionChangesInDb() = runTest {
            val txFlow = MutableStateFlow<Transaction?>(createTransaction(id = 1, amount = 35.0))
            every { transactionRepository.observeById(1L) } returns txFlow

            val vm = TransactionDetailViewModel(createSavedStateHandle(1L), transactionRepository)

            vm.uiState.test {
                awaitItem() // Loading
                val loaded1 = awaitItem() as DetailUiState.Loaded
                assertEquals(35.0, loaded1.transaction.amount)

                // Simulate DB update
                txFlow.value = createTransaction(id = 1, amount = 50.0, note = "更新了")
                val loaded2 = awaitItem() as DetailUiState.Loaded
                assertEquals(50.0, loaded2.transaction.amount)
                assertEquals("更新了", loaded2.transaction.note)

                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_showNotFound_when_transactionDeletedExternally() = runTest {
            val txFlow = MutableStateFlow<Transaction?>(createTransaction(id = 1))
            every { transactionRepository.observeById(1L) } returns txFlow

            val vm = TransactionDetailViewModel(createSavedStateHandle(1L), transactionRepository)

            vm.uiState.test {
                awaitItem() // Loading
                val loaded = awaitItem()
                assertTrue(loaded is DetailUiState.Loaded)

                // Simulate external deletion
                txFlow.value = null
                val notFound = awaitItem()
                assertTrue(notFound is DetailUiState.NotFound)

                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    // ── Delete transaction ───────────────────────────────────────────────

    @Nested
    inner class DeleteTransaction {

        @Test
        fun should_callRepositoryDelete_when_deleteTransactionCalled() = runTest {
            coEvery { transactionRepository.delete(1L) } returns Result.success(Unit)
            val vm = createViewModel(transactionId = 1L)

            var callbackInvoked = false
            vm.deleteTransaction { callbackInvoked = true }
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { transactionRepository.delete(1L) }
        }

        @Test
        fun should_invokeCallback_when_deleteSucceeds() = runTest {
            coEvery { transactionRepository.delete(1L) } returns Result.success(Unit)
            val vm = createViewModel(transactionId = 1L)

            var callbackInvoked = false
            vm.deleteTransaction { callbackInvoked = true }
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(callbackInvoked)
        }

        @Test
        fun should_deleteCorrectId_when_transactionIdFromSavedState() = runTest {
            coEvery { transactionRepository.delete(42L) } returns Result.success(Unit)
            val vm = createViewModel(transactionId = 42L)

            vm.deleteTransaction {}
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { transactionRepository.delete(42L) }
        }
    }

    // ── Edge cases ───────────────────────────────────────────────────────

    @Nested
    inner class EdgeCases {

        @Test
        fun should_showNotFound_when_invalidTransactionId() = runTest {
            every { transactionRepository.observeById(-1L) } returns flowOf(null)
            val vm = TransactionDetailViewModel(
                SavedStateHandle(emptyMap<String, Any>()),
                transactionRepository
            )

            vm.uiState.test {
                awaitItem() // Loading
                val state = awaitItem()
                assertTrue(state is DetailUiState.NotFound)
                cancelAndIgnoreRemainingEvents()
            }
        }

        @Test
        fun should_handleAllTransactionSources_when_displayed() = runTest {
            TransactionSource.entries.forEach { source ->
                val tx = createTransaction(id = source.ordinal.toLong() + 1, source = source)
                val vm = createViewModel(
                    transactionId = source.ordinal.toLong() + 1,
                    transaction = tx
                )

                vm.uiState.test {
                    awaitItem() // Loading
                    val loaded = awaitItem() as DetailUiState.Loaded
                    assertEquals(source, loaded.transaction.source)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        @Test
        fun should_handleNullOptionalFields_when_transactionLoaded() = runTest {
            val tx = createTransaction(
                note = null,
                merchantName = null,
                aiConfidence = null,
                originalInput = null
            )
            val vm = createViewModel(transactionId = 1L, transaction = tx)

            vm.uiState.test {
                awaitItem()
                val loaded = awaitItem() as DetailUiState.Loaded
                assertNull(loaded.transaction.note)
                assertNull(loaded.transaction.merchantName)
                assertNull(loaded.transaction.aiConfidence)
                assertNull(loaded.transaction.originalInput)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }
}
