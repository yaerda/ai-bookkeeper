package com.aibookkeeper.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "monthly_stats")
data class MonthlyStatsEntity(
    @PrimaryKey
    @ColumnInfo(name = "month")
    val month: String,                      // "2026-03"

    @ColumnInfo(name = "totalIncome")
    val totalIncome: Double = 0.0,

    @ColumnInfo(name = "totalExpense")
    val totalExpense: Double = 0.0,

    @ColumnInfo(name = "categoryBreakdown")
    val categoryBreakdown: String = "{}",   // JSON string

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long
)
