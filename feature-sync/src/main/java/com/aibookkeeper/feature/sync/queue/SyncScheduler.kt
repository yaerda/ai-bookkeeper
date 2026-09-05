package com.aibookkeeper.feature.sync.queue

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aibookkeeper.core.data.di.LocalLedger
import com.aibookkeeper.core.data.repository.TransactionRepository
import com.aibookkeeper.feature.sync.auth.AuthManager
import com.aibookkeeper.feature.sync.auth.AuthState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Singleton
class SyncScheduler internal constructor(
    private val workManager: WorkManager,
    private val authManager: AuthManager,
    private val repository: TransactionRepository,
    private val scope: CoroutineScope
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        authManager: AuthManager,
        @LocalLedger repository: TransactionRepository
    ) : this(
        WorkManager.getInstance(context),
        authManager,
        repository,
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    private val started = AtomicBoolean()
    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        schedulePeriodic()
        scope.launch {
            var previousAccountId: String? = null
            combine(
                authManager.state,
                repository.observePendingSyncCount()
            ) { authState, pendingCount ->
                (authState as? AuthState.SignedIn)?.accountId to pendingCount
            }
                .distinctUntilChanged()
                .collect { (accountId, pendingCount) ->
                    val accountChanged = accountId != previousAccountId
                    previousAccountId = accountId
                    if (accountId != null && (accountChanged || pendingCount > 0)) enqueueImmediate()
                }
        }
    }

    fun onLocalCategoryCreated() {
        if (authManager.state.value is AuthState.SignedIn) {
            // The Room write is already durable; scheduling failure must not make the UI retry it.
            // Startup/sign-in and periodic sync also import the complete local catalog.
            runCatching { enqueueImmediate() }
        }
    }

    fun enqueueImmediate() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    private fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            Duration.ofHours(6)
        )
            .setConstraints(networkConstraint)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private companion object {
        const val IMMEDIATE_WORK = "cloud-sync-immediate"
        const val PERIODIC_WORK = "cloud-sync-periodic"
    }
}
