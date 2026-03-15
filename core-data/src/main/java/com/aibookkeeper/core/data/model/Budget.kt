package com.aibookkeeper.core.data.model

import java.time.YearMonth

data class Budget(
    val id: Long = 0,
    val categoryId: Long?,
    val amount: Double,
    val month: YearMonth,
    val spent: Double = 0.0,
    val progress: Float = 0f
)
