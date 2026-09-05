package com.aibookkeeper.core.data.model

val newestTransactionFirst: Comparator<Transaction> =
    compareByDescending<Transaction> { it.date }
        .thenByDescending { it.createdAt }
        .thenByDescending { it.syncId }
