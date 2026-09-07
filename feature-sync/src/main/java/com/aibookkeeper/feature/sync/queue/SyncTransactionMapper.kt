package com.aibookkeeper.feature.sync.queue

import com.aibookkeeper.core.common.extensions.toEpochMillis
import com.aibookkeeper.core.common.extensions.toLocalDateTime
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.feature.sync.network.SyncTransactionDto
import java.util.UUID

internal fun Transaction.toSyncDto() = SyncTransactionDto(
    syncId = syncId,
    serverVersion = serverVersion,
    amount = amount,
    type = type.name,
    categoryId = categoryId,
    categoryName = categoryName,
    categoryIcon = categoryIcon,
    categoryColor = categoryColor,
    merchantName = merchantName,
    note = note,
    originalInput = originalInput,
    date = date.toEpochMillis(),
    createdAt = createdAt.toEpochMillis(),
    updatedAt = updatedAt.toEpochMillis(),
    source = source.name,
    status = status.name,
    aiConfidence = aiConfidence,
    deletedAt = deletedAt?.toEpochMillis(),
    projectIds = projectIds,
    recordedByUserId = recordedByUserId,
    recordedByDisplayName = recordedByDisplayName,
    recordedByEmail = recordedByEmail
)

internal fun SyncTransactionDto.toDomainTransaction() = Transaction(
    id = stableRemoteId(syncId),
    amount = amount,
    type = TransactionType.valueOf(type),
    categoryId = categoryId,
    categoryName = categoryName,
    categoryIcon = categoryIcon,
    categoryColor = categoryColor,
    merchantName = merchantName,
    note = note,
    originalInput = originalInput,
    date = date.toLocalDateTime(),
    createdAt = createdAt.toLocalDateTime(),
    updatedAt = updatedAt.toLocalDateTime(),
    source = TransactionSource.valueOf(source),
    status = TransactionStatus.valueOf(status),
    syncStatus = SyncStatus.SYNCED,
    aiConfidence = aiConfidence,
    syncId = syncId,
    serverVersion = serverVersion,
    deletedAt = deletedAt?.toLocalDateTime(),
    projectIds = projectIds,
    recordedByUserId = recordedByUserId,
    recordedByDisplayName = recordedByDisplayName,
    recordedByEmail = recordedByEmail
)

private fun stableRemoteId(syncId: String): Long {
    val value = runCatching {
        val uuid = UUID.fromString(syncId)
        uuid.mostSignificantBits xor uuid.leastSignificantBits
    }.getOrElse { syncId.hashCode().toLong() }
    return when {
        value == Long.MIN_VALUE -> Long.MIN_VALUE + 1
        value == 0L -> -1L
        value > 0L -> -value
        else -> value
    }
}
