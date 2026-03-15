package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.common.extensions.endOfMonthMillis
import com.aibookkeeper.core.common.extensions.startOfMonthMillis
import com.aibookkeeper.core.common.extensions.toEpochMillis
import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.local.dao.TransactionDao
import com.aibookkeeper.core.data.mapper.TransactionMapper
import com.aibookkeeper.core.data.model.CategoryExpense
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.time.YearMonth
import javax.inject.Inject

class TransactionRepositoryImpl @Inject constructor(
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val mapper: TransactionMapper
) : TransactionRepository {

    override suspend fun create(transaction: Transaction): Result<Long> = runCatching {
        transactionDao.insert(mapper.toEntity(transaction))
    }

    override suspend fun getById(id: Long): Transaction? =
        transactionDao.getById(id)?.let { entity ->
            val category = entity.categoryId?.let { categoryDao.getById(it) }
            mapper.toDomain(entity).copy(
                categoryName = category?.name,
                categoryIcon = category?.icon,
                categoryColor = category?.color
            )
        }

    override fun observeById(id: Long): Flow<Transaction?> =
        transactionDao.observeById(id).map { it?.let(mapper::toDomain) }

    override fun observeByDateRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Transaction>> =
        transactionDao.observeByDateRange(start.toEpochMillis(), end.toEpochMillis())
            .map { entities -> entities.map(mapper::toDomain) }

    override fun observeByMonth(yearMonth: YearMonth): Flow<List<Transaction>> =
        transactionDao.observeByDateRange(yearMonth.startOfMonthMillis(), yearMonth.endOfMonthMillis())
            .map { entities -> entities.map(mapper::toDomain) }

    override fun observePendingTransactions(): Flow<List<Transaction>> =
        transactionDao.observeByStatus(TransactionStatus.PENDING.name)
            .map { entities -> entities.map(mapper::toDomain) }

    override fun observeByCategoryAndMonth(categoryId: Long, yearMonth: YearMonth): Flow<List<Transaction>> =
        transactionDao.observeByCategoryAndDateRange(
            categoryId, yearMonth.startOfMonthMillis(), yearMonth.endOfMonthMillis()
        ).map { entities -> entities.map(mapper::toDomain) }

    override suspend fun update(transaction: Transaction): Result<Unit> = runCatching {
        transactionDao.update(mapper.toEntity(transaction))
    }

    override suspend fun confirmTransaction(id: Long): Result<Unit> = runCatching {
        transactionDao.updateStatus(id, TransactionStatus.CONFIRMED.name, System.currentTimeMillis())
    }

    override suspend fun confirmAll(ids: List<Long>): Result<Unit> = runCatching {
        ids.forEach { id ->
            transactionDao.updateStatus(id, TransactionStatus.CONFIRMED.name, System.currentTimeMillis())
        }
    }

    override suspend fun delete(id: Long): Result<Unit> = runCatching {
        transactionDao.deleteById(id)
    }

    override suspend fun search(keyword: String): List<Transaction> =
        transactionDao.search(keyword).map(mapper::toDomain)

    override fun observeMonthlyIncome(yearMonth: YearMonth): Flow<Double> =
        transactionDao.observeSumByTypeAndDateRange(
            "INCOME", yearMonth.startOfMonthMillis(), yearMonth.endOfMonthMillis()
        )

    override fun observeMonthlyExpense(yearMonth: YearMonth): Flow<Double> =
        transactionDao.observeSumByTypeAndDateRange(
            "EXPENSE", yearMonth.startOfMonthMillis(), yearMonth.endOfMonthMillis()
        )

    override fun observeExpenseBreakdown(yearMonth: YearMonth): Flow<List<CategoryExpense>> =
        transactionDao.observeExpenseBreakdown(
            yearMonth.startOfMonthMillis(), yearMonth.endOfMonthMillis()
        ).map { sums ->
            val total = sums.sumOf { it.total }
            sums.mapNotNull { sum ->
                val category = sum.categoryId?.let { categoryDao.getById(it) }
                CategoryExpense(
                    categoryId = sum.categoryId ?: 0,
                    categoryName = category?.name ?: "其他",
                    categoryColor = category?.color ?: "#607D8B",
                    amount = sum.total,
                    percentage = if (total > 0) (sum.total / total).toFloat() else 0f
                )
            }
        }

    override suspend fun getPendingSync(): List<Transaction> =
        transactionDao.getPendingSyncTransactions().map(mapper::toDomain)

    override suspend fun markSynced(ids: List<Long>) {
        ids.forEach { transactionDao.updateSyncStatus(it, SyncStatus.SYNCED.name) }
    }
}
