package com.aibookkeeper.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aibookkeeper.core.data.local.dao.BudgetDao
import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.local.dao.MonthlyStatsDao
import com.aibookkeeper.core.data.local.dao.PaymentPagePatternDao
import com.aibookkeeper.core.data.local.dao.RawEventDao
import com.aibookkeeper.core.data.local.dao.TransactionDao
import com.aibookkeeper.core.data.local.entity.BudgetEntity
import com.aibookkeeper.core.data.local.entity.CategoryEntity
import com.aibookkeeper.core.data.local.entity.MonthlyStatsEntity
import com.aibookkeeper.core.data.local.entity.PaymentPagePatternEntity
import com.aibookkeeper.core.data.local.entity.RawEventEntity
import com.aibookkeeper.core.data.local.entity.TransactionEntity

@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
        BudgetEntity::class,
        MonthlyStatsEntity::class,
        RawEventEntity::class,
        PaymentPagePatternEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetDao(): BudgetDao
    abstract fun monthlyStatsDao(): MonthlyStatsDao
    abstract fun rawEventDao(): RawEventDao
    abstract fun paymentPagePatternDao(): PaymentPagePatternDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS payment_page_patterns (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        packageName TEXT NOT NULL,
                        appDisplayName TEXT NOT NULL,
                        keywords TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        isEnabled INTEGER NOT NULL DEFAULT 1,
                        isSystem INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_payment_page_patterns_packageName ON payment_page_patterns (packageName)"
                )
                PaymentPagePatternSeedData.insertDefaults(db)
            }
        }
    }
}
