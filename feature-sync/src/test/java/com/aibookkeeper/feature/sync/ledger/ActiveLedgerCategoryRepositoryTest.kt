package com.aibookkeeper.feature.sync.ledger

import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.repository.CategoryRepository
import com.aibookkeeper.core.data.repository.LedgerContextState
import com.aibookkeeper.core.data.repository.LedgerOption
import com.aibookkeeper.core.data.repository.LedgerSelectionChangedException
import com.aibookkeeper.core.data.repository.localLedgerOption
import com.aibookkeeper.feature.sync.queue.SyncScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveLedgerCategoryRepositoryTest {
    private val local = mockk<CategoryRepository>()
    private val session = mockk<SharedLedgerSession>()
    private val syncScheduler = mockk<SyncScheduler>(relaxUnitFun = true)
    private val localOption = localLedgerOption()
    private val shared = LedgerOption("shared", "家庭", "", "EDITOR", "FAMILY", false)
    private val state = MutableStateFlow(
        LedgerContextState(isSignedIn = true, ledgers = listOf(localOption, shared))
    )
    private val localCategory = Category(1, "本地私有", "ic_food", "#FF5722", TransactionType.EXPENSE)
    private val cloudCategory = Category(1, "家庭菜园", "🪴", "#AABBCC", TransactionType.EXPENSE)
    private val categories = MutableStateFlow(listOf(cloudCategory))
    private val repository = ActiveLedgerCategoryRepository(local, session, syncScheduler)

    @BeforeEach
    fun setup() {
        every { session.state } returns state
        every { session.remoteCategories } returns categories
        every { session.canUpdateLocalCategories } answers { state.value.canUpdateCategories }
        every { local.observeAllCategories() } returns flowOf(listOf(localCategory))
    }

    @Test
    fun `catalog flow clears immediately during ledger switching and never merges local categories`() = runTest {
        val emissions = mutableListOf<List<Category>>()
        backgroundScope.launch { repository.observeAllCategories().toList(emissions) }
        runCurrent()
        state.value = state.value.copy(selectedLedgerId = shared.id, isLoading = true, selectionVersion = 1)
        runCurrent()
        state.value = state.value.copy(isLoading = false)
        runCurrent()

        assertEquals(listOf(listOf(localCategory), emptyList(), listOf(cloudCategory)), emissions)
        assertEquals(cloudCategory, repository.getById(1))
        assertNull(repository.findByNameAndType(localCategory.name, TransactionType.EXPENSE))
        coVerify(exactly = 0) { local.getById(any()) }
        verify(exactly = 0) { syncScheduler.onLocalCategoryCreated() }
    }

    @Test
    fun `viewer may read but every direct mutation is rejected`() = runTest {
        state.value = state.value.copy(ledgers = listOf(shared.copy(role = "VIEWER")), selectedLedgerId = shared.id)

        assertEquals(listOf(cloudCategory), repository.observeExpenseCategories().first())
        assertTrue(repository.create(cloudCategory.copy(id = 0)).isFailure)
        assertTrue(repository.update(cloudCategory).isFailure)
        assertTrue(repository.delete(cloudCategory.id).isFailure)
        coVerify(exactly = 0) { session.createCategory(any()) }
        coVerify(exactly = 0) { local.create(any()) }
        coVerify(exactly = 0) { local.update(any()) }
        coVerify(exactly = 0) { local.delete(any()) }
        verify(exactly = 0) { syncScheduler.onLocalCategoryCreated() }
    }

    @Test
    fun `editor creates on server and server errors are propagated`() = runTest {
        state.value = state.value.copy(selectedLedgerId = shared.id)
        coEvery { session.createCategory(any()) } returns 908
        assertEquals(908L, repository.create(cloudCategory.copy(id = 0)).getOrThrow())
        coEvery { session.createCategory(any()) } throws IllegalStateException("分类保存失败")
        assertEquals("分类保存失败", repository.create(cloudCategory.copy(id = 0)).exceptionOrNull()?.message)
        coVerify(exactly = 0) { local.create(any()) }
        verify(exactly = 0) { syncScheduler.onLocalCategoryCreated() }
    }

    @Test
    fun `unused local category schedules sync only after successful durable creation`() = runTest {
        coEvery { local.create(any()) } returns Result.success(51)
        val unused = localCategory.copy(id = 0, name = "未使用的分类", isSystem = false)

        assertEquals(51L, repository.create(unused).getOrThrow())

        coVerifyOrder {
            local.create(unused)
            syncScheduler.onLocalCategoryCreated()
        }
        coVerify(exactly = 0) { session.createCategory(any()) }
    }

    @Test
    fun `failed local creation does not schedule category sync`() = runTest {
        coEvery { local.create(any()) } returns Result.failure(IllegalStateException("Room write failed"))

        assertTrue(repository.create(localCategory.copy(id = 0)).isFailure)

        verify(exactly = 0) { syncScheduler.onLocalCategoryCreated() }
    }

    @Test
    fun `remote rename and delete explicitly fail while local behavior remains available offline`() = runTest {
        state.value = state.value.copy(selectedLedgerId = shared.id)
        assertTrue(repository.update(cloudCategory).isFailure)
        assertTrue(repository.delete(1).isFailure)
        state.value = state.value.copy(
            selectedLedgerId = localOption.id, isSignedIn = false, errorMessage = "offline"
        )
        coEvery { local.update(localCategory) } returns Result.success(Unit)
        coEvery { local.delete(1) } returns Result.success(Unit)
        coEvery { local.create(any()) } returns Result.success(51)
        assertTrue(repository.update(localCategory).isSuccess)
        assertTrue(repository.delete(1).isSuccess)
        assertEquals(51L, repository.create(localCategory.copy(id = 0)).getOrThrow())
        coVerify(exactly = 0) { session.createCategory(any()) }
    }

    @Test
    fun `synced default category metadata cannot diverge even after signing out`() = runTest {
        assertTrue(repository.update(localCategory).isFailure)
        assertTrue(repository.delete(localCategory.id).isFailure)
        state.value = state.value.copy(isSignedIn = false, localCategoriesSynced = true)
        assertTrue(repository.update(localCategory).isFailure)
        assertTrue(repository.delete(localCategory.id).isFailure)
        coVerify(exactly = 0) { local.update(any()) }
        coVerify(exactly = 0) { local.delete(any()) }
    }

    @Test
    fun `cloud catalog remains flat and distinguishes income from expense`() = runTest {
        state.value = state.value.copy(selectedLedgerId = shared.id)
        val income = cloudCategory.copy(id = 2, type = TransactionType.INCOME)
        categories.value = listOf(cloudCategory, income)
        assertEquals(listOf(cloudCategory), repository.observeExpenseCategories().first())
        assertEquals(listOf(income), repository.observeIncomeCategories().first())
        assertEquals(income, repository.findByNameAndType("  家庭菜园 ", TransactionType.INCOME))
        assertEquals(emptyList<Category>(), repository.observeSubCategories(1).first())
    }

    @Test
    fun `suspended local lookup cannot return a previous ledger category`() = runTest {
        val pending = CompletableDeferred<Category>()
        coEvery { local.getById(1) } coAnswers { pending.await() }
        val lookup = async { runCatching { repository.getById(1) } }
        runCurrent()
        state.value = state.value.copy(selectedLedgerId = shared.id, selectionVersion = 1)
        pending.complete(localCategory)

        assertInstanceOf(LedgerSelectionChangedException::class.java, lookup.await().exceptionOrNull())
    }

    @Test
    fun `loading and failed shared catalogs cannot be used to create categories`() = runTest {
        state.value = state.value.copy(selectedLedgerId = shared.id, isLoading = true)
        assertTrue(repository.create(cloudCategory.copy(id = 0)).isFailure)
        assertEquals(emptyList<Category>(), repository.observeAllCategories().first())
        state.value = state.value.copy(isLoading = false, errorMessage = "HTTP 403")
        assertTrue(repository.create(cloudCategory.copy(id = 0)).isFailure)
        assertEquals(emptyList<Category>(), repository.observeAllCategories().first())
    }
}
