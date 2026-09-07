package com.aibookkeeper.feature.input.components

import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.recordedByLabel

fun transactionRecordedBySummary(
    transaction: Transaction,
    showFamily: Boolean,
    showUnknown: Boolean = false
): String? = if (!showFamily) {
    null
} else {
    transaction.recordedByLabel ?: if (showUnknown) "未记录" else null
}

fun joinTransactionMeta(vararg parts: String?): String =
    parts.mapNotNull { it?.trim()?.takeIf { value -> value.isNotEmpty() } }
        .joinToString(" · ")
