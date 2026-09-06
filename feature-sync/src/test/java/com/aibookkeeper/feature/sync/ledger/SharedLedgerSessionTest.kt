package com.aibookkeeper.feature.sync.ledger

import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.repository.LedgerSelectionChangedException
import com.aibookkeeper.core.data.repository.TransactionRepository
import com.aibookkeeper.feature.sync.auth.AccessToken
import com.aibookkeeper.feature.sync.auth.AuthManager
import com.aibookkeeper.feature.sync.auth.AuthState
import com.aibookkeeper.feature.sync.network.CategoriesResponse
import com.aibookkeeper.feature.sync.network.CategoryResponse
import com.aibookkeeper.feature.sync.network.FamilyLedgerDto
import com.aibookkeeper.feature.sync.network.FamilyLedgersResponse
import com.aibookkeeper.feature.sync.network.LedgerCategoryDto
import com.aibookkeeper.feature.sync.network.PullResponse
import com.aibookkeeper.feature.sync.network.PushResponse
import com.aibookkeeper.feature.sync.network.SyncApi
import com.aibookkeeper.feature.sync.queue.LedgerCategorySync
import com.aibookkeeper.feature.sync.queue.SyncPreferences
import com.aibookkeeper.feature.sync.queue.toSyncDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDateTime
import java.time.YearMonth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SharedLedgerSessionTest {
    private val auth = mockk<AuthManager>()
    private val api = mockk<SyncApi>()
    private val categorySync = mockk<LedgerCategorySync>()
    private val preferences = mockk<SyncPreferences>()
    private val savedSelections = mutableMapOf<String, String>()
    private val authState = MutableStateFlow<AuthState>(AuthState.SignedOut)
    private val boundAccount = MutableStateFlow<String?>("account-a")
    private val categoryA = LedgerCategoryDto(601, "家庭菜园", "EXPENSE", "🪴", "#123ABC", 20, false)
    private val categoryB = LedgerCategoryDto(702, "旅行", "EXPENSE", "🚆", "#AABBCC", 10, false)
    private val ledgers = FamilyLedgersResponse(
        ledgers = listOf(
            FamilyLedgerDto("default", "已共享的默认账本", "owner@example.com", "OWNER", "FAMILY", true),
            FamilyLedgerDto("shared-a", "家庭A", "a@example.com", "EDITOR", "FAMILY"),
            FamilyLedgerDto("shared-b", "家庭B", "b@example.com", "VIEWER", "FAMILY"),
            FamilyLedgerDto("new-owned", "新账本", "owner@example.com", "OWNER", "PERSONAL")
        ),
        invitations = emptyList()
    )

    @BeforeEach
    fun setup() {
        every { auth.state } returns authState
        every { preferences.boundAccountId } returns boundAccount
        every { preferences.selectedLedgerId(any()) } answers { savedSelections[firstArg<String>()] }
        every { preferences.updateSelectedLedgerId(any(), any()) } answers {
            savedSelections[firstArg<String>()] = secondArg<String>()
        }
        coEvery { auth.acquireToken() } answers {
            (authState.value as? AuthState.SignedIn)?.let {
                AccessToken("token-${it.accountId}", it.accountId)
            }
        }
        coEvery { categorySync.syncDefault(any()) } returns emptyList()
        coEvery { api.familyLedgers(any()) } returns Response.success(ledgers)
        coEvery { api.categories(any(), any()) } answers {
            Response.success(
                CategoriesResponse(listOf(if (secondArg<String>() == "shared-a") categoryA else categoryB))
            )
        }
        coEvery { api.pull(any(), any(), any(), any()) } returns Response.success(
            PullResponse(emptyList(), 0, false)
        )
    }

    private fun TestScope.start(): SharedLedgerSession {
        val session = SharedLedgerSession(auth, api, categorySync, preferences, backgroundScope)
        authState.value = AuthState.SignedIn("account-a", "owner@example.com")
        runCurrent()
        return session
    }

    @Test
    fun `selected shared ledger survives a new session without importing into Room`() = runTest {
        val session = start()
        session.selectLedger("shared-a")
        runCurrent()

        val restored = SharedLedgerSession(auth, api, categorySync, preferences, backgroundScope)
        runCurrent()

        assertEquals("shared-a", restored.state.value.selectedLedgerId)
        assertFalse(restored.state.value.selectedLedger.isLocal)
        assertEquals(listOf(categoryA.toCategory()), restored.remoteCategories.value)
        coVerify(exactly = 1) { categorySync.syncDefault(any()) }
    }

    @Test
    fun `returning to default replaces the remembered remote ledger`() = runTest {
        val session = start()
        session.selectLedger("new-owned")
        runCurrent()
        session.selectLedger("default")
        runCurrent()

        val restored = SharedLedgerSession(auth, api, categorySync, preferences, backgroundScope)
        runCurrent()

        assertEquals("default", restored.state.value.selectedLedgerId)
        assertTrue(restored.state.value.selectedLedger.isLocal)
        assertTrue(restored.remoteCategories.value.isEmpty())
    }

    @Test
    fun `remembered selection survives logout and stays isolated between accounts`() = runTest {
        val session = start()
        session.selectLedger("shared-a")
        runCurrent()
        authState.value = AuthState.SignedOut
        runCurrent()
        assertTrue(session.state.value.selectedLedger.isLocal)
        assertEquals("shared-a", savedSelections["account-a"])

        authState.value = AuthState.SignedIn("account-b", "b@example.com")
        runCurrent()
        assertEquals("default", session.state.value.selectedLedgerId)
        assertFalse(session.state.value.selectedLedger.isLocal)
        session.selectLedger("shared-b")
        runCurrent()

        authState.value = AuthState.SignedIn("account-a", "owner@example.com")
        runCurrent()
        assertEquals("shared-a", session.state.value.selectedLedgerId)
        authState.value = AuthState.SignedIn("account-b", "b@example.com")
        runCurrent()
        assertEquals("shared-b", session.state.value.selectedLedgerId)
        assertFalse(session.state.value.canEdit)
        assertEquals("shared-a", savedSelections["account-a"])
        coVerify(exactly = 0) { categorySync.syncDefault(match { it.accountId == "account-b" }) }
    }

    @Test
    fun `unavailable remembered ledger falls back to local default`() = runTest {
        savedSelections["account-a"] = "removed-ledger"

        val session = start()

        assertEquals("default", session.state.value.selectedLedgerId)
        assertEquals("default", savedSelections["account-a"])
        assertTrue(session.state.value.selectedLedger.isLocal)
        coVerify(exactly = 0) { api.categories(any(), "removed-ledger") }
    }

    @Test
    fun `ledger list failure preserves remembered selection for retry`() = runTest {
        savedSelections["account-a"] = "shared-a"
        coEvery { api.familyLedgers(any()) } returns Response.error(503, "{}".toResponseBody())
        val session = start()
        assertFalse(session.state.value.errorMessage.isNullOrBlank())
        assertEquals("shared-a", savedSelections["account-a"])

        coEvery { api.familyLedgers(any()) } returns Response.success(ledgers)
        session.refresh()

        assertEquals("shared-a", session.state.value.selectedLedgerId)
        assertEquals(listOf(categoryA.toCategory()), session.remoteCategories.value)
    }

    @Test
    fun `removing the current ledger remembers the accessible fallback`() = runTest {
        val session = start()
        session.selectLedger("shared-a")
        runCurrent()
        coEvery { api.familyLedgers(any()) } returns Response.success(
            ledgers.copy(ledgers = ledgers.ledgers.filterNot { it.id == "shared-a" })
        )

        session.refresh()

        assertEquals("default", session.state.value.selectedLedgerId)
        assertEquals("default", savedSelections["account-a"])
    }

    @Test
    fun `owner shared default stays local and syncs catalog while new owned and invited ledgers never import`() = runTest {
        val session = start()
        assertTrue(session.state.value.selectedLedger.isLocal)
        assertEquals("default", session.state.value.selectedLedgerId)
        coVerify(exactly = 1) { categorySync.syncDefault(match { it.accountId == "account-a" }) }

        session.selectLedger("shared-a")
        runCurrent()
        assertEquals(listOf(categoryA.toCategory()), session.remoteCategories.value)
        session.selectLedger("new-owned")
        runCurrent()

        assertFalse(session.state.value.selectedLedger.isLocal)
        coVerify(exactly = 1) { categorySync.syncDefault(any()) }
        coVerify { api.categories(any(), "shared-a") }
        coVerify { api.categories(any(), "new-owned") }
        coVerify(exactly = 0) { api.importCategories(any(), any(), any()) }
    }

    @Test
    fun `cloud binding immediately locks category metadata and survives logout`() = runTest {
        boundAccount.value = null
        val session = SharedLedgerSession(auth, api, categorySync, preferences, backgroundScope)
        runCurrent()
        assertTrue(session.canUpdateLocalCategories)
        boundAccount.value = "account-a"
        assertFalse(session.canUpdateLocalCategories)
        runCurrent()
        assertFalse(session.state.value.canUpdateCategories)
        authState.value = AuthState.SignedIn("account-a", "owner@example.com")
        runCurrent()
        authState.value = AuthState.SignedOut
        runCurrent()
        assertFalse(session.canUpdateLocalCategories)
        assertTrue(session.state.value.localCategoriesSynced)
    }

    @Test
    fun `shared transaction ties are ordered by creation time and stable ID`() = runTest {
        val session = start()
        val base = transaction(categoryA.name, categoryA.id)
        val older = base.copy(syncId = "a", createdAt = base.createdAt.minusMinutes(1))
        val newer = base.copy(syncId = "b", createdAt = base.createdAt)
        coEvery { api.pull(any(), any(), any(), "shared-a") } returns Response.success(
            PullResponse(listOf(older.toSyncDto(), newer.toSyncDto()), 2, false)
        )
        session.selectLedger("shared-a")
        runCurrent()
        assertEquals(listOf("b", "a"), session.remoteTransactions.value.map { it.syncId })
    }

    @Test
    fun `category and transaction caches clear synchronously on selection and late fetch is ignored`() = runTest {
        val session = start()
        session.selectLedger("shared-a")
        runCurrent()
        val pending = CompletableDeferred<Response<CategoriesResponse>>()
        coEvery { api.categories(any(), "shared-b") } coAnswers { pending.await() }

        session.selectLedger("shared-b")
        assertTrue(session.remoteCategories.value.isEmpty())
        assertTrue(session.remoteTransactions.value.isEmpty())
        assertTrue(session.state.value.isLoading)
        runCurrent()
        session.selectLedger("shared-a")
        runCurrent()
        pending.complete(Response.success(CategoriesResponse(listOf(categoryB))))
        runCurrent()

        assertEquals("shared-a", session.state.value.selectedLedgerId)
        assertEquals(listOf(categoryA.toCategory()), session.remoteCategories.value)
        assertFalse(session.state.value.isLoading)
    }

    @Test
    fun `returning to same ledger does not accept an earlier generation response`() = runTest {
        val session = start()
        val old = CompletableDeferred<Response<CategoriesResponse>>()
        var requests = 0
        val fresh = categoryA.copy(name = "最新分类", icon = "🌱")
        coEvery { api.categories(any(), "shared-a") } coAnswers {
            if (++requests == 1) old.await() else Response.success(CategoriesResponse(listOf(fresh)))
        }
        session.selectLedger("shared-a")
        runCurrent()
        session.selectLedger("shared-b")
        runCurrent()
        session.selectLedger("shared-a")
        runCurrent()
        old.complete(Response.success(CategoriesResponse(listOf(categoryA))))
        runCurrent()

        assertEquals(listOf(fresh.toCategory()), session.remoteCategories.value)
    }

    @Test
    fun `sign out and another account returning to same ledger rejects old account response`() = runTest {
        val session = start()
        val old = CompletableDeferred<Response<CategoriesResponse>>()
        val accountBCatalog = categoryA.copy(name = "账户B分类")
        coEvery { api.categories(any(), "shared-a") } coAnswers {
            if (firstArg<String>().endsWith("account-a")) old.await()
            else Response.success(CategoriesResponse(listOf(accountBCatalog)))
        }
        session.selectLedger("shared-a")
        runCurrent()
        authState.value = AuthState.SignedOut
        runCurrent()
        assertTrue(session.remoteCategories.value.isEmpty())
        authState.value = AuthState.SignedIn("account-b", "b@example.com")
        runCurrent()
        assertFalse(session.state.value.selectedLedger.isLocal)
        session.selectLedger("shared-a")
        runCurrent()
        old.complete(Response.success(CategoriesResponse(listOf(categoryA))))
        runCurrent()

        assertEquals(listOf(accountBCatalog.toCategory()), session.remoteCategories.value)
        coVerify(exactly = 0) { categorySync.syncDefault(match { it.accountId == "account-b" }) }
    }

    @Test
    fun `account mismatch hides local catalog even while ledger list is still loading`() = runTest {
        val session = start()
        val pending = CompletableDeferred<Response<FamilyLedgersResponse>>()
        coEvery { api.familyLedgers(any()) } coAnswers { pending.await() }
        authState.value = AuthState.SignedIn("account-b", "b@example.com")
        runCurrent()

        assertFalse(session.state.value.selectedLedger.isLocal)
        assertTrue(session.state.value.isLoading)
        pending.complete(Response.success(ledgers))
        runCurrent()
        assertFalse(session.state.value.selectedLedger.isLocal)
        coVerify(exactly = 0) { categorySync.syncDefault(match { it.accountId == "account-b" }) }
    }

    @Test
    fun `late ledger refresh cannot undo explicit selection`() = runTest {
        val session = start()
        session.selectLedger("shared-a")
        runCurrent()
        val pending = CompletableDeferred<Response<FamilyLedgersResponse>>()
        coEvery { api.familyLedgers(any()) } coAnswers { pending.await() }
        val refresh = async { session.refresh() }
        runCurrent()
        session.selectLedger("shared-b")
        runCurrent()
        pending.complete(Response.success(ledgers))
        refresh.await()

        assertEquals("shared-b", session.state.value.selectedLedgerId)
        assertEquals(listOf(categoryB.toCategory()), session.remoteCategories.value)
        assertEquals("shared-b", savedSelections["account-a"])
    }

    @Test
    fun `late failed fetch cannot publish error into newly selected ledger`() = runTest {
        val session = start()
        val pending = CompletableDeferred<Response<CategoriesResponse>>()
        coEvery { api.categories(any(), "shared-a") } coAnswers { pending.await() }
        session.selectLedger("shared-a")
        runCurrent()
        session.selectLedger("shared-b")
        runCurrent()
        pending.complete(Response.error(403, "{}".toResponseBody()))
        runCurrent()

        assertEquals("shared-b", session.state.value.selectedLedgerId)
        assertNull(session.state.value.errorMessage)
        assertEquals(listOf(categoryB.toCategory()), session.remoteCategories.value)
    }

    @Test
    fun `viewer direct category and transaction writes are rejected without network writes`() = runTest {
        val session = start()
        session.selectLedger("shared-b")
        runCurrent()

        assertTrue(runCatching { session.createCategory(categoryB.toCategory().copy(id = 0)) }.isFailure)
        assertTrue(runCatching { session.push(transaction("旅行", categoryB.id)) }.isFailure)
        coVerify(exactly = 0) { api.createCategory(any(), any(), any()) }
        coVerify(exactly = 0) { api.push(any(), any(), any()) }
    }

    @Test
    fun `editor creates category using server canonical id and metadata without duplicating existing name`() = runTest {
        val session = start()
        session.selectLedger("shared-a")
        runCurrent()
        coEvery { api.createCategory(any(), any(), "shared-a") } returns Response.success(CategoryResponse(categoryA))

        val id = session.createCategory(categoryA.toCategory().copy(id = 0, icon = "🌱"))

        assertEquals(601L, id)
        assertEquals(listOf(categoryA.toCategory()), session.remoteCategories.value)
        coVerify { api.createCategory(any(), match { it.icon == "🌱" }, "shared-a") }
    }

    @Test
    fun `create category response after switching ledgers cannot enter new catalog`() = runTest {
        val session = start()
        session.selectLedger("shared-a")
        runCurrent()
        val pending = CompletableDeferred<Response<CategoryResponse>>()
        coEvery { api.createCategory(any(), any(), "shared-a") } coAnswers { pending.await() }
        val create = async { runCatching { session.createCategory(categoryA.toCategory().copy(id = 0)) } }
        runCurrent()
        session.selectLedger("shared-b")
        runCurrent()
        pending.complete(Response.success(CategoryResponse(categoryA.copy(id = 800, name = "新分类"))))

        assertInstanceOf(LedgerSelectionChangedException::class.java, create.await().exceptionOrNull())
        assertEquals(listOf(categoryB.toCategory()), session.remoteCategories.value)
    }

    @Test
    fun `remote transactions map sender local IDs by catalog name and type for editing and stats`() = runTest {
        val session = start()
        val second = categoryA.copy(id = 602, name = "宠物", icon = "🐈")
        coEvery { api.categories(any(), "shared-a") } returns Response.success(
            CategoriesResponse(listOf(categoryA, second))
        )
        coEvery { api.pull(any(), any(), any(), "shared-a") } returns Response.success(
            PullResponse(
                listOf(transaction(categoryA.name, 1).toSyncDto(), transaction(second.name, 1).toSyncDto()),
                2, false
            )
        )
        session.selectLedger("shared-a")
        runCurrent()

        assertEquals(setOf(601L, 602L), session.remoteTransactions.value.map { it.categoryId }.toSet())
        assertEquals(setOf("🪴", "🐈"), session.remoteTransactions.value.map { it.categoryIcon }.toSet())
        val repository = ActiveLedgerTransactionRepository(mockk<TransactionRepository>(), session)
        val breakdown = repository.observeExpenseBreakdown(YearMonth.of(2026, 9)).first()
        assertEquals(setOf(601L, 602L), breakdown.map { it.categoryId }.toSet())
        assertEquals(1, repository.observeByCategoryAndMonth(601, YearMonth.of(2026, 9)).first().size)
    }

    @Test
    fun `late transaction push does not populate another ledger or reuse obsolete category IDs`() = runTest {
        val session = start()
        session.selectLedger("shared-a")
        runCurrent()
        val pending = CompletableDeferred<Response<PushResponse>>()
        coEvery { api.push(any(), any(), "shared-a") } coAnswers { pending.await() }
        val transaction = transaction(categoryA.name, categoryA.id)
        val push = async { runCatching { session.push(transaction) } }
        runCurrent()
        session.selectLedger("shared-b")
        runCurrent()
        pending.complete(Response.success(PushResponse(listOf(transaction.toSyncDto()), emptyList())))

        assertInstanceOf(LedgerSelectionChangedException::class.java, push.await().exceptionOrNull())
        assertTrue(session.remoteTransactions.value.isEmpty())
        assertEquals(listOf(categoryB.toCategory()), session.remoteCategories.value)
    }

    private fun transaction(name: String, categoryId: Long) = Transaction(
        amount = 20.0,
        type = TransactionType.EXPENSE,
        categoryId = categoryId,
        categoryName = name,
        categoryIcon = "obsolete",
        categoryColor = "#000000",
        date = LocalDateTime.of(2026, 9, 5, 12, 0),
        createdAt = LocalDateTime.of(2026, 9, 5, 12, 0),
        updatedAt = LocalDateTime.of(2026, 9, 5, 12, 0),
        source = TransactionSource.MANUAL,
        status = TransactionStatus.CONFIRMED,
        syncStatus = SyncStatus.SYNCED
    )
}
