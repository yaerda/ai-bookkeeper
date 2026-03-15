package com.aibookkeeper.feature.sync.queue

import com.aibookkeeper.core.data.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import javax.inject.Inject

interface SyncManager {
    suspend fun syncNow(): Result<SyncReport>
    fun observeSyncState(): Flow<SyncState>
    fun observePendingCount(): Flow<Int>
}

data class SyncReport(
    val uploaded: Int,
    val downloaded: Int,
    val conflicts: Int,
    val timestamp: Instant
)

enum class SyncState {
    IDLE, SYNCING, SUCCESS, ERROR
}

/**
 * No-op implementation for v1.0. All data stays local.
 */
class NoOpSyncManager @Inject constructor() : SyncManager {
    override suspend fun syncNow() = Result.success(
        SyncReport(0, 0, 0, Instant.now())
    )
    override fun observeSyncState() = flowOf(SyncState.IDLE)
    override fun observePendingCount() = flowOf(0)
}
