package com.aibookkeeper.feature.sync.queue

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aibookkeeper.feature.sync.auth.AuthenticationRequiredException
import com.aibookkeeper.feature.sync.auth.RetryableAuthenticationException
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val manager = EntryPointAccessors.fromApplication(
            applicationContext,
            SyncWorkerEntryPoint::class.java
        ).syncManager()

        return manager.syncNow().fold(
            onSuccess = { report ->
                val pendingCount = manager.observePendingCount().first()
                if (shouldRetryAfterSync(report, pendingCount)) {
                    Result.retry()
                } else {
                    Result.success()
                }
            },
            onFailure = { error ->
                when (error) {
                    is AuthenticationRequiredException,
                    is AccountMismatchException -> Result.failure()
                    else -> if (isRetryableSyncFailure(error)) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            }
        )
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncWorkerEntryPoint {
        fun syncManager(): SyncManager
    }
}

internal fun isRetryableSyncFailure(error: Throwable): Boolean =
    error is java.io.IOException || error is RetryableAuthenticationException

internal fun shouldRetryAfterSync(report: SyncReport, pendingCount: Int): Boolean =
    report.conflicts > 0 || pendingCount > report.failed
