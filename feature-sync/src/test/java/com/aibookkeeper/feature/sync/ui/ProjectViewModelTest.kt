package com.aibookkeeper.feature.sync.ui

import androidx.lifecycle.viewModelScope
import com.aibookkeeper.core.data.model.ProjectLedgerState
import com.aibookkeeper.core.data.model.ProjectScope
import com.aibookkeeper.core.data.model.ProjectStats
import com.aibookkeeper.core.data.repository.LedgerContext
import com.aibookkeeper.core.data.repository.LedgerContextState
import com.aibookkeeper.core.data.repository.ProjectRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectViewModelTest {
    private val projects = mockk<ProjectRepository>()
    private val ledgers = mockk<LedgerContext>()
    private val ledgerState = MutableStateFlow(LedgerContextState(isSignedIn = true, accountId = "account"))
    private val projectState = MutableStateFlow(ProjectLedgerState(accountId = "account", ledgerId = "ledger"))
    private lateinit var viewModel: ProjectViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { ledgers.state } returns ledgerState
        every { projects.currentLedgerState } returns projectState
        coEvery { projects.loadProjectStats(any(), any()) } answers { stats(firstArg()) }
        viewModel = ProjectViewModel(ledgers, projects)
    }

    @AfterEach
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `late selection response cannot replace current details or clear current loading`() = runTest {
        runCurrent()
        val old = CompletableDeferred<ProjectScope>()
        val newest = CompletableDeferred<ProjectScope>()
        coEvery { projects.loadProjectScope("old") } coAnswers { withContext(NonCancellable) { old.await() } }
        coEvery { projects.loadProjectScope("new") } coAnswers { newest.await() }
        viewModel.selectProject("old")
        runCurrent()
        viewModel.selectProject("new")
        runCurrent()
        old.complete(scope("old"))
        runCurrent()
        assertTrue(viewModel.isLoading.value)
        assertNull(viewModel.selectedScope.value)
        newest.complete(scope("new"))
        runCurrent()
        assertEquals("new", viewModel.selectedScope.value?.projectId)
        assertEquals("new", viewModel.selectedStats.value?.projectId)
        assertFalse(viewModel.isLoading.value)
        assertNull(viewModel.message.value)
    }

    @Test
    fun `logout clears loaded details and rejects in flight statistics`() = runTest {
        runCurrent()
        coEvery { projects.loadProjectScope(any()) } answers { scope(firstArg()) }
        viewModel.selectProject("loaded")
        runCurrent()
        assertEquals("loaded", viewModel.selectedScope.value?.projectId)
        val late = CompletableDeferred<ProjectStats>()
        coEvery { projects.loadProjectStats("late", any()) } coAnswers { withContext(NonCancellable) { late.await() } }
        viewModel.selectProject("late")
        runCurrent()
        ledgerState.value = ledgerState.value.copy(isSignedIn = false, accountId = null, selectionVersion = 2)
        projectState.value = ProjectLedgerState(contextVersion = 2)
        runCurrent()
        late.complete(stats("late"))
        runCurrent()
        assertNull(viewModel.selectedProjectId.value)
        assertNull(viewModel.selectedScope.value)
        assertNull(viewModel.selectedStats.value)
        assertNull(viewModel.message.value)
        assertFalse(viewModel.isLoading.value)
    }

    @Test
    fun `late failure from previous ledger is not shown in current context`() = runTest {
        runCurrent()
        val late = CompletableDeferred<ProjectScope>()
        coEvery { projects.loadProjectScope(any()) } coAnswers { withContext(NonCancellable) { late.await() } }
        viewModel.selectProject("project")
        runCurrent()
        ledgerState.value = ledgerState.value.copy(selectedLedgerId = "other", selectionVersion = 2)
        runCurrent()
        late.completeExceptionally(IllegalStateException("old failure"))
        runCurrent()
        assertNull(viewModel.message.value)
        assertFalse(viewModel.isLoading.value)
    }

    private fun scope(id: String) = ProjectScope(id, id, emptyList())
    private fun stats(id: String) = ProjectStats(id, id, "CNY", 0, "0", "0", "0", emptyList())
}
