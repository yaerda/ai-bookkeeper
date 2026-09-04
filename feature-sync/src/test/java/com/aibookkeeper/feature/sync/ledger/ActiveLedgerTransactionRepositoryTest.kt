package com.aibookkeeper.feature.sync.ledger

import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.repository.LedgerContextState
import com.aibookkeeper.core.data.repository.LedgerOption
import com.aibookkeeper.core.data.repository.TransactionRepository
import com.aibookkeeper.core.data.repository.localLedgerOption
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDateTime
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveLedgerTransactionRepositoryTest {

    private val localRepository = mockk<TransactionRepository>()
    private val session = mockk<SharedLedgerSession>()

    @Test
    fun `month flow switches from local Room to selected shared ledger`() = runTest {
        val month = YearMonth.of(2026, 9)
        val local = transaction(id = 1, amount = 10.0, category = "餐饮")
        val shared = transaction(id = -2, amount = 20.0, category = "购物")
        val localOption = localLedgerOption()
        val sharedOption = LedgerOption(
            id = "shared-ledger",
            name = "家庭账本",
            ownerEmail = "owner@example.com",
            role = "EDITOR",
            mode = "FAMILY",
            isLocal = false
        )
        val state = MutableStateFlow(
            LedgerContextState(
                isSignedIn = true,
                ledgers = listOf(localOption, sharedOption),
                selectedLedgerId = localOption.id
            )
        )
        val remote = MutableStateFlow(listOf(shared))
        every { session.state } returns state
        every { session.remoteTransactions } returns remote
        every { localRepository.observeByMonth(month) } returns flowOf(listOf(local))
        val repository = ActiveLedgerTransactionRepository(localRepository, session)
        val emissions = mutableListOf<List<Transaction>>()

        val collection = launch {
            repository.observeByMonth(month).take(2).toList(emissions)
        }
        advanceUntilIdle()
        state.value = state.value.copy(selectedLedgerId = sharedOption.id)
        advanceUntilIdle()
        collection.join()

        assertEquals(listOf(listOf(local), listOf(shared)), emissions)
    }

    @Test
    fun `shared category breakdown keeps categories with no server category id separate`() = runTest {
        val month = YearMonth.of(2026, 9)
        val sharedOption = LedgerOption(
            id = "shared-ledger",
            name = "家庭账本",
            ownerEmail = "owner@example.com",
            role = "VIEWER",
            mode = "FAMILY",
            isLocal = false
        )
        every { session.state } returns MutableStateFlow(
            LedgerContextState(
                isSignedIn = true,
                ledgers = listOf(sharedOption),
                selectedLedgerId = sharedOption.id
            )
        )
        every { session.remoteTransactions } returns MutableStateFlow(
            listOf(
                transaction(id = -1, amount = 12.0, category = "餐饮"),
                transaction(id = -2, amount = 8.0, category = "购物")
            )
        )
        val repository = ActiveLedgerTransactionRepository(localRepository, session)

        val breakdown = repository.observeExpenseBreakdown(month)
            .take(1)
            .toList()
            .single()

        assertEquals(listOf("餐饮", "购物"), breakdown.map { it.categoryName })
        assertEquals(2, breakdown.map { it.categoryId }.distinct().size)
    }

    private fun transaction(
        id: Long,
        amount: Double,
        category: String
    ) = Transaction(
        id = id,
        amount = amount,
        type = TransactionType.EXPENSE,
        categoryId = null,
        categoryName = category,
        date = LocalDateTime.of(2026, 9, 4, 12, 0),
        createdAt = LocalDateTime.of(2026, 9, 4, 12, 0),
        updatedAt = LocalDateTime.of(2026, 9, 4, 12, 0),
        source = TransactionSource.MANUAL,
        status = TransactionStatus.CONFIRMED,
        syncStatus = SyncStatus.SYNCED
    )
}
