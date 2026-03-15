package com.aibookkeeper.core.data.mapper

import com.aibookkeeper.core.common.extensions.toLocalDateTime
import com.aibookkeeper.core.common.extensions.toEpochMillis
import com.aibookkeeper.core.data.local.entity.TransactionEntity
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import javax.inject.Inject

class TransactionMapper @Inject constructor() {

    fun toDomain(entity: TransactionEntity): Transaction = Transaction(
        id = entity.id,
        amount = entity.amount,
        type = TransactionType.valueOf(entity.type),
        categoryId = entity.categoryId,
        merchantName = entity.merchantName,
        note = entity.note,
        originalInput = entity.originalInput,
        date = entity.date.toLocalDateTime(),
        createdAt = entity.createdAt.toLocalDateTime(),
        updatedAt = entity.updatedAt.toLocalDateTime(),
        source = TransactionSource.valueOf(entity.source),
        status = TransactionStatus.valueOf(entity.status),
        syncStatus = SyncStatus.valueOf(entity.syncStatus),
        aiConfidence = entity.aiConfidence
    )

    fun toEntity(domain: Transaction): TransactionEntity = TransactionEntity(
        id = domain.id,
        amount = domain.amount,
        type = domain.type.name,
        categoryId = domain.categoryId,
        merchantName = domain.merchantName,
        note = domain.note,
        originalInput = domain.originalInput,
        date = domain.date.toEpochMillis(),
        createdAt = domain.createdAt.toEpochMillis(),
        updatedAt = domain.updatedAt.toEpochMillis(),
        source = domain.source.name,
        status = domain.status.name,
        syncStatus = domain.syncStatus.name,
        aiConfidence = domain.aiConfidence
    )
}
