package com.aibookkeeper.feature.sync.queue

import com.aibookkeeper.core.data.repository.TransactionRepository
import com.aibookkeeper.core.data.di.LocalLedger
import com.aibookkeeper.core.common.extensions.toEpochMillis
import com.aibookkeeper.core.common.extensions.toLocalDateTime
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.feature.sync.auth.AuthenticationRequiredException
import com.aibookkeeper.feature.sync.auth.TokenProvider
import com.aibookkeeper.feature.sync.network.PullResponse
import com.aibookkeeper.feature.sync.network.PushRequest
import com.aibookkeeper.feature.sync.network.PushResponse
import com.aibookkeeper.feature.sync.network.SyncApi
import com.aibookkeeper.feature.sync.network.SyncTransactionDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

interface SyncManager {
    suspend fun syncNow(): Result<SyncReport>
    fun observeSyncState(): Flow<SyncState>
    fun observePendingCount(): Flow<Int>
}

data class SyncReport(
    val uploaded: Int,
    val downloaded: Int,
    val conflicts: Int,
    val failedSyncIds: Set<String>,
    val timestamp: Instant
) {
    val failed: Int
        get() = failedSyncIds.size
}

enum class SyncState {
    IDLE, SYNCING, SUCCESS, ERROR
}

class AccountMismatchException :
    IllegalStateException("当前本地账本已绑定其他账户，为防止数据混用，本次同步已停止")
class PermanentSyncException(message: String) : IllegalStateException(message)
class RetryableSyncException(message: String) : IOException(message)

@Singleton
class CloudSyncManager @Inject constructor(
    @LocalLedger
    private val repository: TransactionRepository,
    private val api: SyncApi,
    private val tokenProvider: TokenProvider,
    private val preferences: SyncPreferences,
    private val json: Json
) : SyncManager {

    private val mutex = Mutex()
    private val state = MutableStateFlow(SyncState.IDLE)

    override suspend fun syncNow(): Result<SyncReport> = mutex.withLock {
        state.value = SyncState.SYNCING
        runCatching {
            val token = tokenProvider.acquireToken()
                ?: throw AuthenticationRequiredException()
            if (!preferences.bindAccount(token.accountId)) {
                throw AccountMismatchException()
            }

            val firstUpload = uploadPending(
                token.value,
                repository.getPendingSync()
            )
            var uploaded = firstUpload.uploaded
            var unresolvedConflictIds = firstUpload.conflictIds
            var failedIds = firstUpload.failedIds
            if (unresolvedConflictIds.isNotEmpty()) {
                val retryItems = repository.getPendingSync()
                    .filter { it.syncId in unresolvedConflictIds }
                val retry = uploadPending(token.value, retryItems)
                uploaded += retry.uploaded
                unresolvedConflictIds = retry.conflictIds
                failedIds = failedIds + retry.failedIds
            }

            var cursor = preferences.cursor()
            var downloaded = 0
            var pullConflicts = 0
            do {
                val page = pull(token.value, cursor)
                page.transactions.forEach { remote ->
                    if (repository.mergeRemote(remote.toDomainTransaction())) {
                        downloaded++
                    } else {
                        pullConflicts++
                    }
                }
                if (page.hasMore && page.nextCursor <= cursor) {
                    throw IOException("同步游标没有前进")
                }
                cursor = page.nextCursor
                preferences.updateCursor(cursor)
            } while (page.hasMore)

            SyncReport(
                uploaded = uploaded,
                downloaded = downloaded,
                conflicts = unresolvedConflictIds.size + pullConflicts,
                failedSyncIds = failedIds,
                timestamp = Instant.now()
            )
        }.onSuccess {
            state.value = if (it.conflicts == 0 && it.failed == 0) {
                SyncState.SUCCESS
            } else {
                SyncState.ERROR
            }
        }.onFailure {
            if (it is CancellationException) {
                state.value = SyncState.IDLE
                throw it
            }
            state.value = if (it is AuthenticationRequiredException) {
                SyncState.IDLE
            } else {
                SyncState.ERROR
            }
        }
    }

    override fun observeSyncState(): Flow<SyncState> = state.asStateFlow()

    override fun observePendingCount(): Flow<Int> =
        repository.observePendingSyncCount()

    private suspend fun uploadPending(
        accessToken: String,
        pending: List<Transaction>
    ): UploadSummary {
        return pending.chunked(MAX_PUSH_BATCH_SIZE).fold(UploadSummary()) { total, batch ->
            total + uploadBatchWithIsolation(accessToken, batch)
        }
    }

    private suspend fun uploadBatchWithIsolation(
        accessToken: String,
        batch: List<Transaction>
    ): UploadSummary = try {
        val conflictIds = mutableSetOf<String>()
        var uploaded = 0
        run {
            val response = pushBatch(accessToken, batch)
            val pendingBySyncId = batch.associateBy { it.syncId }
            response.accepted.forEach { accepted ->
                val original = pendingBySyncId[accepted.syncId] ?: return@forEach
                if (repository.acknowledgeSynced(
                        syncId = accepted.syncId,
                        expectedUpdatedAt = original.updatedAt,
                        expectedServerVersion = original.serverVersion,
                        serverVersion = accepted.serverVersion
                    )
                ) {
                    uploaded++
                } else {
                    conflictIds += accepted.syncId
                }
            }
            response.conflicts.forEach { conflict ->
                val original = pendingBySyncId[conflict.syncId] ?: return@forEach
                repository.rebasePendingSync(
                    syncId = conflict.syncId,
                    expectedServerVersion = original.serverVersion,
                    serverVersion = conflict.serverVersion
                )
                conflictIds += conflict.syncId
            }
            UploadSummary(uploaded, conflictIds)
        }
    } catch (error: PermanentSyncException) {
        if (batch.size == 1) {
            UploadSummary(failedIds = setOf(batch.single().syncId))
        } else {
            val middle = batch.size / 2
            uploadBatchWithIsolation(accessToken, batch.subList(0, middle)) +
                uploadBatchWithIsolation(accessToken, batch.subList(middle, batch.size))
        }
    }

    private suspend fun pushBatch(
        accessToken: String,
        pending: List<Transaction>
    ): PushResponse {
        val response = api.push(
            authorization = "Bearer $accessToken",
            request = PushRequest(pending.map(Transaction::toSyncDto))
        )
        response.body()?.let { return it }
        if (response.code() == 409) {
            val errorBody = response.errorBody()?.string()
                ?: throw IOException("同步冲突响应为空")
            return json.decodeFromString<PushResponse>(errorBody)
        }
        if (response.code() == 401 || response.code() == 403) {
            tokenProvider.invalidate()
            throw AuthenticationRequiredException()
        }
        throw response.toSyncException("上传")
    }

    private suspend fun pull(accessToken: String, cursor: Long): PullResponse {
        val response = api.pull("Bearer $accessToken", cursor)
        if (response.code() == 401 || response.code() == 403) {
            tokenProvider.invalidate()
            throw AuthenticationRequiredException()
        }
        return response.body()
            ?: throw response.toSyncException("下载")
    }

    private data class UploadSummary(
        val uploaded: Int = 0,
        val conflictIds: Set<String> = emptySet(),
        val failedIds: Set<String> = emptySet()
    ) {
        operator fun plus(other: UploadSummary) = UploadSummary(
            uploaded = uploaded + other.uploaded,
            conflictIds = conflictIds + other.conflictIds,
            failedIds = failedIds + other.failedIds
        )
    }

    private companion object {
        const val MAX_PUSH_BATCH_SIZE = 200
    }
}

private fun retrofit2.Response<*>.toSyncException(operation: String): Exception =
    if (code() == 408 || code() == 429 || code() in 500..599) {
        RetryableSyncException("$operation 暂时失败：HTTP ${code()}")
    } else {
        PermanentSyncException("$operation 请求被拒绝：HTTP ${code()}")
    }
