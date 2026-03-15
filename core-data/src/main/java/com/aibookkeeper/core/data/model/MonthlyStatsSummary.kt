package com.aibookkeeper.core.data.model

import java.time.LocalDate
import java.time.YearMonth

data class MonthlyStatsSummary(
    val month: YearMonth,
    val totalIncome: Double,
    val totalExpense: Double,
    val balance: Double,
    val categoryBreakdown: List<CategoryExpense>,
    val dailyExpenses: List<DailyExpense>
)

data class CategoryExpense(
    val categoryId: Long,
    val categoryName: String,
    val categoryColor: String,
    val amount: Double,
    val percentage: Float
)

data class DailyExpense(
    val date: LocalDate,
    val amount: Double
)
