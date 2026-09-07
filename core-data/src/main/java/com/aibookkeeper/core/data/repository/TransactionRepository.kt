package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.data.model.CategoryExpense
import com.aibookkeeper.core.data.model.Transaction
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import java.time.YearMonth

data class TransactionMonthSummary(
    val month: YearMonth,
    val count: Int
)

interface TransactionRepository {

    suspend fun create(transaction: Transaction): Result<Long>

    suspend fun getById(id: Long): Transaction?

    fun observeById(id: Long): Flow<Transaction?>

    fun observeByDateRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Transaction>>

    fun observeByMonth(yearMonth: YearMonth): Flow<List<Transaction>>

    fun observeTransactionMonths(): Flow<List<TransactionMonthSummary>>

    fun observePendingTransactions(): Flow<List<Transaction>>

    fun observePendingSyncCount(): Flow<Int>

    fun observeByCategoryAndMonth(categoryId: Long, yearMonth: YearMonth): Flow<List<Transaction>>

    suspend fun update(transaction: Transaction): Result<Unit>

    suspend fun confirmTransaction(id: Long): Result<Unit>

    suspend fun confirmAll(ids: List<Long>): Result<Unit>

    suspend fun delete(id: Long): Result<Unit>

    suspend fun search(keyword: String): List<Transaction>

    fun observeMonthlyIncome(yearMonth: YearMonth): Flow<Double>

    fun observeMonthlyExpense(yearMonth: YearMonth): Flow<Double>

    fun observeExpenseBreakdown(yearMonth: YearMonth): Flow<List<CategoryExpense>>

    suspend fun getPendingSync(): List<Transaction>

    suspend fun markSynced(ids: List<Long>)

    suspend fun acknowledgeSynced(
        syncId: String,
        expectedUpdatedAt: LocalDateTime,
        expectedServerVersion: Long,
        serverVersion: Long,
        recordedByUserId: String? = null,
        recordedByDisplayName: String? = null,
        recordedByEmail: String? = null
    ): Boolean

    suspend fun rebasePendingSync(
        syncId: String,
        expectedServerVersion: Long,
        serverVersion: Long
    ): Boolean

    suspend fun mergeRemote(transaction: Transaction): Boolean

    suspend fun needsRecordedByMetadataRefresh(): Boolean = false

    suspend fun refreshRecordedByMetadata(transaction: Transaction): Boolean = false

    suspend fun getMonthlyExpense(yearMonth: YearMonth): Double

    suspend fun getCategoryBreakdownOnce(type: String, yearMonth: YearMonth): List<CategoryExpense>
}
