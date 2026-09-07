package com.aibookkeeper.feature.sync.projects

import android.content.Context
import android.content.SharedPreferences
import com.aibookkeeper.core.data.model.ProjectDefaultsAvailability
import com.aibookkeeper.core.data.model.ProjectDraft
import com.aibookkeeper.core.data.repository.LedgerContext
import com.aibookkeeper.core.data.repository.LedgerContextState
import com.aibookkeeper.core.data.repository.LedgerOption
import com.aibookkeeper.feature.sync.auth.AccessToken
import com.aibookkeeper.feature.sync.auth.AuthManager
import com.aibookkeeper.feature.sync.auth.AuthState
import com.aibookkeeper.feature.sync.network.ProjectBindingDto
import com.aibookkeeper.feature.sync.network.ProjectsResponse
import com.aibookkeeper.feature.sync.network.ProjectScopeResponse
import com.aibookkeeper.feature.sync.network.SyncApi
import com.aibookkeeper.feature.sync.queue.SyncPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Response
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveProjectRepositoryTest {
    private val authManager = mockk<AuthManager>()
    private val ledgerContext = mockk<LedgerContext>()
    private val api = mockk<SyncApi>()
    private val authState = MutableStateFlow<AuthState>(AuthState.SignedOut)
    private val ledgerState = MutableStateFlow(LedgerContextState())
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private lateinit var preferences: SyncPreferences

    @BeforeEach
    fun setUp() {
        every { authManager.state } returns authState
        every { ledgerContext.state } returns ledgerState
        coEvery { authManager.acquireToken() } answers {
            val signedIn = authState.value as? AuthState.SignedIn
            signedIn?.let { AccessToken("token-${it.accountId}", it.accountId) }
        }
        preferences = inMemoryPreferences()
    }

    @Test
    fun `uses cached defaults when refresh fails for the same account and ledger`() = runTest {
        cache("account-a", "ledger-1", "EDITOR", listOf(binding("project-a", active = true)))
        val option = LedgerOption("ledger-1", "家庭账本", "owner@example.com", "EDITOR", "FAMILY", false)
        ledgerState.value = LedgerContextState(
            accountId = "account-a",
            isSignedIn = true,
            ledgers = listOf(option),
            selectedLedgerId = option.id
        )
        authState.value = AuthState.SignedIn("account-a", "owner@example.com")
        coEvery { api.projects(any(), "ledger-1") } returns Response.error(
            503,
            "{}".toResponseBody()
        )
        val repository = start(backgroundScope)
        runCurrent()

        assertEquals(ProjectDefaultsAvailability.CACHED, repository.currentLedgerState.value.availability)
        assertEquals(listOf("project-a"), repository.resolveProjectIdsForNewTransaction())
        assertTrue(repository.currentLedgerState.value.errorMessage?.contains("项目服务") == true)
    }

    @Test
    fun `account switch keeps project caches isolated`() = runTest {
        cache("account-a", "ledger-1", "EDITOR", listOf(binding("project-a", name = "装修")))
        cache("account-b", "ledger-1", "VIEWER", listOf(binding("project-b", name = "旅行", canEdit = false)))
        val option = LedgerOption("ledger-1", "共享账本", "owner@example.com", "EDITOR", "FAMILY", false)
        ledgerState.value = LedgerContextState(
            accountId = "account-a",
            isSignedIn = true,
            ledgers = listOf(option),
            selectedLedgerId = option.id
        )
        authState.value = AuthState.SignedIn("account-a", "a@example.com")
        coEvery { api.projects(any(), "ledger-1") } returnsMany listOf(
            Response.error(503, "{}".toResponseBody()),
            Response.error(503, "{}".toResponseBody())
        )
        val repository = start(backgroundScope)
        runCurrent()
        assertEquals(listOf("装修"), repository.currentLedgerState.value.projects.map { it.name })

        authState.value = AuthState.SignedIn("account-b", "b@example.com")
        ledgerState.value = ledgerState.value.copy(accountId = "account-b", ledgers = listOf(option.copy(role = "VIEWER")))
        runCurrent()

        assertEquals("account-b", repository.currentLedgerState.value.accountId)
        assertEquals(listOf("旅行"), repository.currentLedgerState.value.projects.map { it.name })
    }

    @Test
    fun `signed-out or purely local ledger leaves defaults unavailable`() = runTest {
        val repository = start(backgroundScope)
        runCurrent()
        assertNull(repository.resolveProjectIdsForNewTransaction())

        val localOption = LedgerOption("local-personal-ledger", "个人账本", "", "OWNER", "PERSONAL", true)
        ledgerState.value = LedgerContextState(
            isSignedIn = false,
            ledgers = listOf(localOption),
            selectedLedgerId = localOption.id
        )
        runCurrent()
        assertNull(repository.resolveProjectIdsForNewTransaction())
    }

    @Test
    fun `immediate selection and auth changes cannot resolve stale defaults before collectors run`() = runTest {
        signIn()
        cache("account-a", "ledger-1", "EDITOR", listOf(binding("old")))
        coEvery { api.projects(any(), any()) } returns Response.error(503, "{}".toResponseBody())
        val repository = start(backgroundScope)
        runCurrent()
        assertEquals(listOf("old"), repository.resolveProjectIdsForNewTransaction())
        ledgerState.value = ledgerState.value.copy(
            ledgers = ledgerState.value.ledgers + option("ledger-2"),
            selectedLedgerId = "ledger-2", selectionVersion = 2
        )
        assertNull(repository.resolveProjectIdsForNewTransaction())
        authState.value = AuthState.SignedIn("account-b", "b@example.com")
        assertNull(repository.resolveProjectIdsForNewTransaction())
        authState.value = AuthState.SignedOut
        assertNull(repository.resolveProjectIdsForNewTransaction())
    }

    @Test
    fun `capture uses original default Room projects not selected online ledger`() = runTest {
        signIn(listOf(option("ledger-1"), option("default", local = true)))
        preferences.bindAccount("account-a")
        cache("account-a", "ledger-1", "EDITOR", listOf(binding("online")))
        cache("account-a", "default", "OWNER", listOf(binding("room").copy(ledgerId = "default", enabled = false)))
        coEvery { api.projects(any(), any()) } returns Response.error(503, "{}".toResponseBody())
        val repository = start(backgroundScope)
        runCurrent()
        val destination = repository.captureDestination(defaultRoom = true)
        assertEquals("default", repository.defaultLedgerState.value.ledgerId)
        assertEquals(emptyList<String>(), repository.resolveProjectIds(destination, null))
        assertEquals(listOf("room"), repository.resolveProjectIds(destination, listOf("room")))
        assertThrows(IllegalStateException::class.java) { repository.resolveProjectIds(destination, listOf("online")) }
        authState.value = AuthState.SignedIn("account-b", "b@example.com")
        ledgerState.value = ledgerState.value.copy(accountId = "account-b")
        val otherAccount = repository.captureDestination(defaultRoom = true)
        assertNull(repository.resolveProjectIds(otherAccount, null))
        assertThrows(IllegalStateException::class.java) { repository.resolveProjectIds(otherAccount, listOf("room")) }
        assertEquals("account-a", preferences.boundAccountId.value)
    }

    @Test
    fun `valid empty cache remains complete on network failure`() = runTest {
        signIn()
        cache("account-a", "ledger-1", "EDITOR", emptyList())
        coEvery { api.projects(any(), any()) } returns Response.error(503, "{}".toResponseBody())
        val repository = start(backgroundScope)
        runCurrent()
        assertEquals(ProjectDefaultsAvailability.CACHED, repository.currentLedgerState.value.availability)
        assertEquals(emptyList<String>(), repository.resolveProjectIdsForNewTransaction())
    }

    @Test
    fun `late refresh cannot overwrite a newer request or publish its error`() = runTest {
        signIn()
        coEvery { api.projects(any(), any()) } returns Response.success(ProjectsResponse("ledger-1", "EDITOR", emptyList()))
        val repository = start(backgroundScope)
        runCurrent()
        val older = CompletableDeferred<Response<ProjectsResponse>>()
        coEvery { api.projects(any(), any()) } coAnswers { withContext(NonCancellable) { older.await() } }
        val first = async { runCatching { repository.refreshCurrentLedger() } }
        runCurrent()
        val newest = binding("new").copy(version = 4)
        coEvery { api.projects(any(), any()) } returns Response.success(ProjectsResponse("ledger-1", "EDITOR", listOf(newest)))
        repository.refreshCurrentLedger()
        older.complete(Response.success(ProjectsResponse("ledger-1", "EDITOR", listOf(binding("old")))))
        runCurrent()
        assertTrue(first.await().isFailure)
        assertEquals(listOf("new"), repository.resolveProjectIdsForNewTransaction())
        assertFalse(repository.currentLedgerState.value.isLoading)
        assertNull(repository.currentLedgerState.value.errorMessage)
        assertTrue(preferences.projectCacheJson("account-a", "ledger-1")!!.contains("\"new\""))
    }

    @Test
    fun `logout during late request cannot populate previously absent cache`() = runTest {
        signIn()
        val response = CompletableDeferred<Response<ProjectsResponse>>()
        coEvery { api.projects(any(), any()) } coAnswers { withContext(NonCancellable) { response.await() } }
        val repository = start(backgroundScope)
        runCurrent()
        authState.value = AuthState.SignedOut
        response.complete(Response.success(ProjectsResponse("ledger-1", "EDITOR", listOf(binding("late")))))
        runCurrent()
        assertNull(preferences.projectCacheJson("account-a", "ledger-1"))
        assertTrue(repository.currentLedgerState.value.projects.isEmpty())
    }

    @Test
    fun `partial scope never fabricates complete defaults and older scope never regresses binding`() = runTest {
        signIn()
        coEvery { api.projects(any(), any()) } returns Response.error(503, "{}".toResponseBody())
        coEvery { api.projectScope(any(), "project") } returns Response.success(
            ProjectScopeResponse("project", "project", listOf(binding("project").copy(version = 5)))
        )
        val repository = start(backgroundScope)
        runCurrent()
        repository.loadProjectScope("project")
        assertNull(preferences.projectCacheJson("account-a", "ledger-1"))
        assertNull(repository.resolveProjectIdsForNewTransaction())

        coEvery { api.projects(any(), any()) } returns Response.success(
            ProjectsResponse("ledger-1", "EDITOR", listOf(binding("project"), binding("other")))
        )
        repository.refreshCurrentLedger()
        assertEquals(setOf("project", "other"), repository.resolveProjectIdsForNewTransaction()!!.toSet())
        assertEquals(5, repository.currentLedgerState.value.projects.first { it.projectId == "project" }.version)
        coEvery { api.projectScope(any(), "project") } returns Response.success(
            ProjectScopeResponse("project", "project", listOf(binding("project").copy(version = 2)))
        )
        assertEquals(5, repository.loadProjectScope("project").ledgers.single().version)
        assertEquals(2, repository.currentLedgerState.value.projects.size)
    }

    @Test
    fun `explicit target snapshot is sent in full and empty or nonwritable targets fail`() = runTest {
        signIn()
        coEvery { api.projects(any(), any()) } returns Response.error(503, "{}".toResponseBody())
        val repository = start(backgroundScope)
        runCurrent()
        coEvery { api.createProject(any(), match { it.ledgerIds == listOf("ledger-1") }) } returns Response.success(
            ProjectScopeResponse("project", "project", listOf(binding("project")))
        )
        repository.createProject(ProjectDraft("project", listOf("ledger-1")))
        assertTrue(runCatching { repository.createProject(ProjectDraft("project", emptyList())) }.isFailure)
        assertTrue(runCatching { repository.createProject(ProjectDraft("project", listOf("other"))) }.isFailure)
        assertTrue(runCatching { repository.createProject(ProjectDraft("project", List(101) { "ledger-1" })) }.isFailure)
    }

    @Test
    fun `mutation response after logout is rejected without cache writes`() = runTest {
        signIn()
        coEvery { api.projects(any(), any()) } returns Response.error(503, "{}".toResponseBody())
        val repository = start(backgroundScope)
        runCurrent()
        val response = CompletableDeferred<Response<ProjectBindingDto>>()
        coEvery { api.updateProjectBinding(any(), any(), any(), any()) } coAnswers { response.await() }
        val mutation = async { runCatching { repository.updateProjectBinding("project", "ledger-1", 1, true, null, null) } }
        runCurrent()
        authState.value = AuthState.SignedOut
        response.complete(Response.success(binding("project").copy(version = 2)))
        runCurrent()
        assertTrue(mutation.await().isFailure)
        assertNull(preferences.projectCacheJson("account-a", "ledger-1"))
    }

    @Test
    fun `wrong ledger or role in full response is rejected`() = runTest {
        signIn()
        coEvery { api.projects(any(), any()) } returns Response.success(
            ProjectsResponse("other", "OWNER", listOf(binding("project")))
        )
        val repository = start(backgroundScope)
        runCurrent()
        assertNull(repository.resolveProjectIdsForNewTransaction())
        assertNull(preferences.projectCacheJson("account-a", "ledger-1"))
    }

    @Test
    fun `ordinary save reevaluates cached eligibility with injected wall clock`() = runTest {
        signIn()
        var instant = Instant.parse("2026-09-07T15:59:59Z")
        cache("account-a", "ledger-1", "EDITOR", listOf(binding("dated").copy(
            startDate = "2026-09-08", endDate = "2026-09-08", active = false
        )))
        coEvery { api.projects(any(), any()) } returns Response.error(503, "{}".toResponseBody())
        val repository = ActiveProjectRepository(authManager, ledgerContext, api, preferences, json, backgroundScope) { instant }
        runCurrent()
        assertEquals(emptyList<String>(), repository.resolveProjectIdsForNewTransaction())
        instant = Instant.parse("2026-09-07T16:00:00Z")
        assertEquals(listOf("dated"), repository.resolveProjectIdsForNewTransaction())
        instant = Instant.parse("2026-09-08T16:00:00Z")
        assertEquals(emptyList<String>(), repository.resolveProjectIdsForNewTransaction())
        assertEquals(listOf("dated"), repository.resolveProjectIds(repository.captureDestination(), listOf("dated")))
    }

    @Test
    fun `cancelled current refresh rethrows cancellation without error or stuck loading`() = runTest {
        signIn()
        coEvery { api.projects(any(), any()) } returns Response.success(ProjectsResponse("ledger-1", "EDITOR", emptyList()))
        val repository = start(backgroundScope)
        runCurrent()
        coEvery { api.projects(any(), any()) } throws CancellationException("cancelled")
        assertTrue(runCatching { repository.refreshCurrentLedger() }.exceptionOrNull() is CancellationException)
        assertFalse(repository.currentLedgerState.value.isLoading)
        assertNull(repository.currentLedgerState.value.errorMessage)
    }

    private fun option(id: String, local: Boolean = false) =
        LedgerOption(id, id, "owner@example.com", if (local) "OWNER" else "EDITOR", "FAMILY", local)

    private fun signIn(options: List<LedgerOption> = listOf(option("ledger-1"))) {
        authState.value = AuthState.SignedIn("account-a", "a@example.com")
        ledgerState.value = LedgerContextState(
            accountId = "account-a", isSignedIn = true, ledgers = options,
            selectedLedgerId = options.first().id
        )
    }

    private fun TestScope.start(scope: kotlinx.coroutines.CoroutineScope) = ActiveProjectRepository(
        authManager = authManager,
        ledgerContext = ledgerContext,
        api = api,
        preferences = preferences,
        json = json,
        scope = scope
    )

    private fun cache(accountId: String, ledgerId: String, role: String, projects: List<ProjectBindingDto>) {
        preferences.updateProjectCache(
            accountId,
            ledgerId,
            json.encodeToString(
                CachePayload.serializer(),
                CachePayload(role = role, projects = projects)
            ),
            123L
        )
    }

    private fun binding(
        projectId: String,
        name: String = projectId,
        active: Boolean = false,
        canEdit: Boolean = true
    ) = ProjectBindingDto(
        projectId = projectId,
        ledgerId = "ledger-1",
        name = name,
        enabled = true,
        startDate = null,
        endDate = null,
        timeZone = "Asia/Shanghai",
        version = 1,
        active = active,
        canEdit = canEdit
    )

    private fun inMemoryPreferences(): SyncPreferences {
        val context = mockk<Context>()
        val sharedPreferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        val strings = mutableMapOf<String, String?>()
        val booleans = mutableMapOf<String, Boolean>()
        val longs = mutableMapOf<String, Long>()
        every { context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE) } returns sharedPreferences
        every { sharedPreferences.getString(any(), any()) } answers {
            strings[firstArg<String>()] ?: secondArg<String?>()
        }
        every { sharedPreferences.getBoolean(any(), any()) } answers {
            booleans[firstArg<String>()] ?: secondArg<Boolean>()
        }
        every { sharedPreferences.contains(any()) } answers {
            val key = firstArg<String>()
            longs.containsKey(key)
        }
        every { sharedPreferences.getLong(any(), any()) } answers {
            longs[firstArg<String>()] ?: secondArg<Long>()
        }
        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            strings[firstArg<String>()] = secondArg<String?>()
            editor
        }
        every { editor.putBoolean(any(), any()) } answers {
            booleans[firstArg<String>()] = secondArg<Boolean>()
            editor
        }
        every { editor.putLong(any(), any()) } answers {
            longs[firstArg<String>()] = secondArg<Long>()
            editor
        }
        every { editor.apply() } returns Unit
        return SyncPreferences(context)
    }

    @Serializable
    private data class CachePayload(
        val role: String? = null,
        val projects: List<ProjectBindingDto> = emptyList(),
        val complete: Boolean = true
    )
}
