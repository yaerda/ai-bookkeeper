package com.aibookkeeper.navigation

import app.cash.turbine.test
import com.aibookkeeper.feature.sync.auth.AuthManager
import com.aibookkeeper.feature.sync.auth.AuthState
import com.aibookkeeper.feature.sync.queue.SyncPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerAccessViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val authManager = mockk<AuthManager>(relaxed = true)
    private val syncPreferences = mockk<SyncPreferences>()
    private val authState = MutableStateFlow<AuthState>(AuthState.SignedOut)
    private val boundAccountId = MutableStateFlow<String?>(null)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { authManager.state } returns authState
        every { syncPreferences.boundAccountId } returns boundAccountId
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `signed out user can view local ledger`() = runTest {
        val viewModel = LedgerAccessViewModel(authManager, syncPreferences)

        viewModel.accessState.test {
            assertEquals(LedgerAccessState.Loading, awaitItem())
            assertEquals(LedgerAccessState.Allowed, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `matching signed in account can view bound ledger`() = runTest {
        authState.value = AuthState.SignedIn("account-a", "a@example.com")
        boundAccountId.value = "account-a"
        val viewModel = LedgerAccessViewModel(authManager, syncPreferences)

        viewModel.accessState.test {
            awaitItem()
            assertEquals(LedgerAccessState.Allowed, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `different signed in account cannot view bound ledger`() = runTest {
        authState.value = AuthState.SignedIn("account-b", "b@example.com")
        boundAccountId.value = "account-a"
        val viewModel = LedgerAccessViewModel(authManager, syncPreferences)

        viewModel.accessState.test {
            awaitItem()
            assertEquals(
                LedgerAccessState.AccountMismatch("b@example.com"),
                awaitItem()
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
