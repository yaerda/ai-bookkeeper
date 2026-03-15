package com.aibookkeeper.core.data.model

import java.time.LocalDateTime

data class Transaction(
    val id: Long = 0,
    val amount: Double,
    val type: TransactionType,
    val categoryId: Long?,
    val categoryName: String? = null,
    val categoryIcon: String? = null,
    val categoryColor: String? = null,
    val merchantName: String? = null,
    val note: String? = null,
    val originalInput: String? = null,
    val date: LocalDateTime,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val source: TransactionSource,
    val status: TransactionStatus,
    val syncStatus: SyncStatus,
    val aiConfidence: Float? = null
)
