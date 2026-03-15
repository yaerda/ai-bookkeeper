package com.aibookkeeper.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aibookkeeper.core.data.local.entity.BudgetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(budget: BudgetEntity): Long

    @Update
    suspend fun update(budget: BudgetEntity)

    @Delete
    suspend fun delete(budget: BudgetEntity)

    @Query("SELECT * FROM budgets WHERE month = :month AND categoryId IS NULL LIMIT 1")
    fun observeMonthlyTotal(month: String): Flow<BudgetEntity?>

    @Query("SELECT * FROM budgets WHERE month = :month AND categoryId IS NOT NULL")
    fun observeCategoryBudgets(month: String): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE month = :month")
    fun observeAllForMonth(month: String): Flow<List<BudgetEntity>>
}
