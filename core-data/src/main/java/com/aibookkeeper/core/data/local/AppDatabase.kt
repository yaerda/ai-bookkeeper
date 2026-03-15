package com.aibookkeeper.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aibookkeeper.core.data.local.dao.BudgetDao
import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.local.dao.MonthlyStatsDao
import com.aibookkeeper.core.data.local.dao.TransactionDao
import com.aibookkeeper.core.data.local.entity.BudgetEntity
import com.aibookkeeper.core.data.local.entity.CategoryEntity
import com.aibookkeeper.core.data.local.entity.MonthlyStatsEntity
import com.aibookkeeper.core.data.local.entity.TransactionEntity

@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
        BudgetEntity::class,
        MonthlyStatsEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetDao(): BudgetDao
    abstract fun monthlyStatsDao(): MonthlyStatsDao
}
