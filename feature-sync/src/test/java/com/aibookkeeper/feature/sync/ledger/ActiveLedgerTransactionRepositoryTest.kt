package com.aibookkeeper.feature.sync.ledger

import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.repository.LedgerContextState
import com.aibookkeeper.core.data.repository.LedgerOption
import com.aibookkeeper.core.data.repository.ProjectRepository
import com.aibookkeeper.core.data.repository.ProjectWriteDestination
import com.aibookkeeper.core.data.repository.LedgerSelectionChangedException
import com.aibookkeeper.core.data.repository.TransactionRepository
import com.aibookkeeper.core.data.repository.localLedgerOption
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveLedgerTransactionRepositoryTest {

    private val localRepository = mockk<TransactionRepository>()
    private val projectRepository = mockk<ProjectRepository>()
    private val session = mockk<SharedLedgerSession>()

    @BeforeEach
    fun projectDestinations() {
        coEvery { localRepository.createValidated(any(), any()) } coAnswers {
            secondArg<() -> Unit>().invoke()
            localRepository.create(firstArg())
        }
        coEvery { localRepository.updateValidated(any(), any()) } coAnswers {
            secondArg<() -> Unit>().invoke()
            localRepository.update(firstArg())
        }
        every { projectRepository.captureDestination(any()) } answers {
            ProjectWriteDestination("account", "room", defaultRoom = firstArg())
        }
        every { projectRepository.requireCurrentDestination(any()) } returns Unit
        every { projectRepository.resolveProjectIds(any(), any()) } answers {
            secondArg<List<String>?>() ?: projectRepository.resolveProjectIdsForNewTransaction()
        }
    }

    @Test
    fun `background capture and notification undo remain local while a viewer ledger is selected`() = runTest {
        val viewer = LedgerOption("shared", "家庭", "", "VIEWER", "FAMILY", false)
        every { session.state } returns MutableStateFlow(
            LedgerContextState(isSignedIn = true, ledgers = listOf(viewer), selectedLedgerId = viewer.id)
        )
        val captured = transaction(0, 20.0, "本地分类").copy(
            categoryId = 51, source = TransactionSource.AUTO_CAPTURE
        )
        every { projectRepository.resolveProjectIdsForNewTransaction() } returns null
        coEvery { localRepository.create(captured) } returns Result.success(99)
        coEvery { localRepository.getById(99) } returns captured.copy(id = 99)
        coEvery { localRepository.delete(99) } returns Result.success(Unit)
        val repository = ActiveLedgerTransactionRepository(localRepository, projectRepository, session)

        assertEquals(99L, repository.create(captured).getOrThrow())
        repository.delete(99).getOrThrow()

        coVerify(exactly = 0) { session.push(any(), any()) }
        coVerify { localRepository.create(match { it.categoryId == 51L }) }
        coVerify { localRepository.delete(99) }
    }

    @Test
    fun `capture batch stays atomic and local with destination validation inside persistence`() = runTest {
        every { projectRepository.resolveProjectIdsForNewTransaction() } returns emptyList()
        val captured = listOf(
            transaction(0, 10.0, "capture").copy(source = TransactionSource.AUTO_CAPTURE),
            transaction(0, 20.0, "capture").copy(source = TransactionSource.AUTO_CAPTURE)
        )
        var validationCalls = 0
        coEvery { localRepository.createAllValidated(any(), any()) } answers {
            secondArg<() -> Unit>().invoke()
            assertTrue(firstArg<List<Transaction>>().all { it.projectIds == emptyList<String>() })
            Result.success(listOf(1L, 2L))
        }
        val repository = ActiveLedgerTransactionRepository(localRepository, projectRepository, session)
        assertEquals(listOf(1L, 2L), repository.createAllValidated(captured) { validationCalls++ }.getOrThrow())
        assertEquals(1, validationCalls)
        coVerify(exactly = 1) { localRepository.createAllValidated(any(), any()) }
        coVerify(exactly = 0) { localRepository.create(any()) }
        coVerify(exactly = 0) { session.push(any(), any()) }
    }

    @Test
    fun `capture batch cannot mix destination snapshots`() = runTest {
        val first = transaction(0, 10.0, "capture").copy(
            source = TransactionSource.AUTO_CAPTURE,
            projectDestination = ProjectWriteDestination("a", "default-a", defaultRoom = true)
        )
        val second = first.copy(
            syncId = "second",
            projectDestination = ProjectWriteDestination("b", "default-b", defaultRoom = true)
        )
        val repository = ActiveLedgerTransactionRepository(localRepository, projectRepository, session)
        assertTrue(repository.createAllValidated(listOf(first, second)) {}.isFailure)
        coVerify(exactly = 0) { localRepository.createAllValidated(any(), any()) }
    }

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
        val repository = ActiveLedgerTransactionRepository(localRepository, projectRepository, session)
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
        val repository = ActiveLedgerTransactionRepository(localRepository, projectRepository, session)

        val breakdown = repository.observeExpenseBreakdown(month)
            .take(1)
            .toList()
            .single()

        assertEquals(listOf("餐饮", "购物"), breakdown.map { it.categoryName })
        assertEquals(2, breakdown.map { it.categoryId }.distinct().size)
    }

    @Test
    fun `new transactions capture current project defaults exactly once`() = runTest {
        val localOption = localLedgerOption()
        every { session.state } returns MutableStateFlow(
            LedgerContextState(
                isSignedIn = true,
                ledgers = listOf(localOption),
                selectedLedgerId = localOption.id
            )
        )
        every { projectRepository.resolveProjectIdsForNewTransaction() } returns emptyList()
        val draft = transaction(id = 1, amount = 18.0, category = "餐饮").copy(
            syncStatus = SyncStatus.LOCAL,
            projectIds = null
        )
        coEvery { localRepository.create(match { it.projectIds == emptyList<String>() }) } returns Result.success(8)
        val repository = ActiveLedgerTransactionRepository(localRepository, projectRepository, session)

        val id = repository.create(draft).getOrThrow()

        assertEquals(8L, id)
        coVerify { localRepository.create(match { it.projectIds == emptyList<String>() }) }
    }

    @Test
    fun `viewer cannot resolve defaults before permission check`() = runTest {
        val viewer = LedgerOption("shared", "家庭", "", "VIEWER", "FAMILY", false)
        every { session.state } returns MutableStateFlow(
            LedgerContextState(isSignedIn = true, ledgers = listOf(viewer), selectedLedgerId = viewer.id)
        )
        val repository = ActiveLedgerTransactionRepository(localRepository, projectRepository, session)
        assertTrue(repository.create(transaction(0, 1.0, "餐饮")).isFailure)
        verify(exactly = 0) { projectRepository.captureDestination(any()) }
    }

    @Test
    fun `capture validates explicit IDs and never falls through to a remote write`() = runTest {
        every { session.state } returns MutableStateFlow(LedgerContextState())
        every { projectRepository.resolveProjectIds(any(), listOf("foreign")) } throws
            IllegalStateException("项目不属于目标账本")
        val repository = ActiveLedgerTransactionRepository(localRepository, projectRepository, session)
        assertTrue(repository.create(transaction(0, 1.0, "餐饮").copy(
            source = TransactionSource.AUTO_CAPTURE, projectIds = listOf("foreign")
        )).isFailure)
        coVerify(exactly = 0) { localRepository.create(any()) }
        coVerify(exactly = 0) { session.push(any(), any()) }
    }

    @Test
    fun `selection change during project resolution is checked again before persistence`() = runTest {
        val state = MutableStateFlow(LedgerContextState())
        every { session.state } returns state
        every { projectRepository.resolveProjectIds(any(), any()) } answers {
            state.value = state.value.copy(selectionVersion = 2)
            listOf("old")
        }
        val repository = ActiveLedgerTransactionRepository(localRepository, projectRepository, session)
        val result = repository.create(transaction(0, 1.0, "餐饮"))
        assertTrue(result.exceptionOrNull() is LedgerSelectionChangedException)
        coVerify(exactly = 0) { localRepository.create(any()) }
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
