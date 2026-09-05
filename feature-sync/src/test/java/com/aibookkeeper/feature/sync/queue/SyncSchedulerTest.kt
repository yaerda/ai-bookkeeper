package com.aibookkeeper.feature.sync.queue

import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.aibookkeeper.core.data.repository.TransactionRepository
import com.aibookkeeper.feature.sync.auth.AuthManager
import com.aibookkeeper.feature.sync.auth.AuthState
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncSchedulerTest {
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val authManager = mockk<AuthManager>()
    private val repository = mockk<TransactionRepository>()
    private val authState = MutableStateFlow<AuthState>(AuthState.SignedOut)
    private val pendingCount = MutableStateFlow(0)
    private val signedIn = AuthState.SignedIn("account-a", "owner@example.com")

    @BeforeEach
    fun setup() {
        every { authManager.state } returns authState
        every { repository.observePendingSyncCount() } returns pendingCount
    }

    private fun TestScope.scheduler() =
        SyncScheduler(workManager, authManager, repository, backgroundScope)

    @Test
    fun `unused category immediately enqueues persistent network constrained work with retry backoff`() = runTest {
        authState.value = signedIn
        val request = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(
                "cloud-sync-immediate", ExistingWorkPolicy.APPEND_OR_REPLACE, capture(request)
            )
        } returns mockk(relaxed = true)

        scheduler().onLocalCategoryCreated()

        assertEquals(NetworkType.CONNECTED, request.captured.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.captured.workSpec.backoffPolicy)
        assertEquals(30_000L, request.captured.workSpec.backoffDelayDuration)
        assertEquals(SyncWorker::class.java.name, request.captured.workSpec.workerClassName)
        verify(exactly = 0) { repository.observePendingSyncCount() }
    }

    @Test
    fun `signed out category creation is uploaded on sign in even with zero pending transactions`() = runTest {
        val scheduler = scheduler()
        scheduler.start()
        scheduler.onLocalCategoryCreated()
        runCurrent()
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        }

        authState.value = signedIn
        runCurrent()

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                "cloud-sync-immediate", ExistingWorkPolicy.APPEND_OR_REPLACE, any<OneTimeWorkRequest>()
            )
        }
    }

    @Test
    fun `signed in startup schedules category-only sync once and preserves periodic fallback`() = runTest {
        authState.value = signedIn
        val scheduler = scheduler()

        scheduler.start()
        scheduler.start()
        runCurrent()

        verify(exactly = 1) {
            workManager.enqueueUniquePeriodicWork(
                "cloud-sync-periodic", ExistingPeriodicWorkPolicy.KEEP, any<PeriodicWorkRequest>()
            )
        }
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `signing in again retries catalog import without transaction changes or read driven loops`() = runTest {
        authState.value = signedIn
        val scheduler = scheduler()
        scheduler.start()
        runCurrent()
        authState.value = signedIn.copy(email = "updated@example.com")
        runCurrent()
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        }

        authState.value = AuthState.SignedOut
        runCurrent()
        authState.value = signedIn
        runCurrent()

        verify(exactly = 2) {
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `category changes append work so an in-flight sync cannot lose a later unused category`() = runTest {
        authState.value = signedIn
        val requests = mutableListOf<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(
                "cloud-sync-immediate", ExistingWorkPolicy.APPEND_OR_REPLACE, capture(requests)
            )
        } returns mockk(relaxed = true)
        val scheduler = scheduler()

        scheduler.onLocalCategoryCreated()
        scheduler.onLocalCategoryCreated()

        assertEquals(2, requests.size)
        assertNotEquals(requests[0].id, requests[1].id)
    }

    @Test
    fun `scheduling failure does not report a durable category save as failed and startup retries`() = runTest {
        authState.value = signedIn
        every {
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        } throws IllegalStateException("scheduler unavailable")
        val scheduler = scheduler()

        assertDoesNotThrow { scheduler.onLocalCategoryCreated() }

        every {
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        } returns mockk(relaxed = true)
        scheduler.start()
        runCurrent()
        verify(exactly = 2) {
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `transaction changes still trigger sync but reaching zero does not create a loop`() = runTest {
        authState.value = signedIn
        scheduler().start()
        runCurrent()
        pendingCount.value = 1
        runCurrent()
        pendingCount.value = 0
        runCurrent()

        verify(exactly = 2) {
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        }
    }
}
