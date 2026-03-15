package com.aibookkeeper.core.data.di

import android.content.Context
import androidx.room.Room
import com.aibookkeeper.core.common.constants.AppConstants
import com.aibookkeeper.core.data.local.AppDatabase
import com.aibookkeeper.core.data.local.PrepopulateCallback
import com.aibookkeeper.core.data.local.dao.BudgetDao
import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.local.dao.MonthlyStatsDao
import com.aibookkeeper.core.data.local.dao.RawEventDao
import com.aibookkeeper.core.data.local.dao.TransactionDao
import com.aibookkeeper.core.data.local.migration.Migrations
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppConstants.DATABASE_NAME
        )
            .addMigrations(*Migrations.ALL)
            .addCallback(PrepopulateCallback())
            .build()
    }

    @Provides
    fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun provideCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun provideBudgetDao(db: AppDatabase): BudgetDao = db.budgetDao()

    @Provides
    fun provideMonthlyStatsDao(db: AppDatabase): MonthlyStatsDao = db.monthlyStatsDao()

    @Provides
    fun provideRawEventDao(db: AppDatabase): RawEventDao = db.rawEventDao()
}
