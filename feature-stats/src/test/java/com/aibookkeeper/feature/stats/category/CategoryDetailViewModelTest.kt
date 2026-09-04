package com.aibookkeeper.feature.stats.category

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<TransactionRepository>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads selected category transactions and total`() = runTest {
        val month = YearMonth.of(2026, 8)
        val transactions = listOf(
            transaction(1, 12.5),
            transaction(2, 7.5)
        )
        every {
            repository.observeByCategoryAndMonth(3L, month)
        } returns flowOf(transactions)

        val viewModel = CategoryDetailViewModel(
            SavedStateHandle(
                mapOf(
                    "categoryId" to 3L,
                    "yearMonth" to month.toString()
                )
            ),
            repository
        )

        viewModel.uiState.test {
            awaitItem()
            val loaded = awaitItem()
            assertEquals("餐饮", loaded.categoryName)
            assertEquals(2, loaded.transactions.size)
            assertEquals(20.0, loaded.total)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun transaction(id: Long, amount: Double): Transaction {
        val now = LocalDateTime.of(2026, 8, 24, 12, 0)
        return Transaction(
            id = id,
            amount = amount,
            type = TransactionType.EXPENSE,
            categoryId = 3,
            categoryName = "餐饮",
            date = now,
            createdAt = now,
            updatedAt = now,
            source = TransactionSource.MANUAL,
            status = TransactionStatus.CONFIRMED,
            syncStatus = SyncStatus.SYNCED
        )
    }
}
