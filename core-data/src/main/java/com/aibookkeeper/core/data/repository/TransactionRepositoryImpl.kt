package com.aibookkeeper.core.data.repository

import com.aibookkeeper.core.common.extensions.endOfMonthMillis
import com.aibookkeeper.core.common.extensions.startOfMonthMillis
import com.aibookkeeper.core.common.extensions.toEpochMillis
import com.aibookkeeper.core.common.extensions.toLocalDateTime
import com.aibookkeeper.core.data.local.dao.CategoryDao
import com.aibookkeeper.core.data.local.dao.TransactionDao
import com.aibookkeeper.core.data.mapper.TransactionMapper
import com.aibookkeeper.core.data.model.CategoryExpense
import com.aibookkeeper.core.data.model.SyncStatus
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
        transactionDao.insert(
            mapper.toEntity(
                transaction.copy(
                    syncStatus = if (transaction.syncStatus == SyncStatus.SYNCED) {
                        SyncStatus.PENDING_SYNC
                    } else {
                        transaction.syncStatus
                    },
                    deletedAt = null
                )
            )
        )
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
        transactionDao.observeById(id).withCategoryChanges().map { entity ->
            entity?.let { enrichWithCategory(mapper.toDomain(it)) }
        }

    override fun observeByDateRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Transaction>> =
        transactionDao.observeByDateRange(start.toEpochMillis(), end.toEpochMillis())
            .withCategoryChanges()
            .map { entities -> entities.map { enrichWithCategory(mapper.toDomain(it)) } }

    override fun observeByMonth(yearMonth: YearMonth): Flow<List<Transaction>> =
        transactionDao.observeByDateRange(yearMonth.startOfMonthMillis(), yearMonth.endOfMonthMillis())
            .withCategoryChanges()
            .map { entities -> entities.map { enrichWithCategory(mapper.toDomain(it)) } }

    override fun observeTransactionMonths(): Flow<List<TransactionMonthSummary>> =
        transactionDao.observeActiveTransactionDates().map { dates ->
            dates.groupingBy { YearMonth.from(it.toLocalDateTime()) }
                .eachCount()
                .entries
                .sortedByDescending { it.key }
                .map { (month, count) -> TransactionMonthSummary(month, count) }
        }

    override fun observePendingTransactions(): Flow<List<Transaction>> =
        transactionDao.observeByStatus(TransactionStatus.PENDING.name)
            .withCategoryChanges()
            .map { entities -> entities.map { enrichWithCategory(mapper.toDomain(it)) } }

    override fun observePendingSyncCount(): Flow<Int> =
        transactionDao.observePendingSyncCount()

    override fun observeByCategoryAndMonth(categoryId: Long, yearMonth: YearMonth): Flow<List<Transaction>> =
        transactionDao.observeByCategoryAndDateRange(
            categoryId.takeIf { it > 0 },
            yearMonth.startOfMonthMillis(),
            yearMonth.endOfMonthMillis()
        ).withCategoryChanges().map { entities -> entities.map { enrichWithCategory(mapper.toDomain(it)) } }

    private fun <T> Flow<T>.withCategoryChanges(): Flow<T> =
        combine(categoryDao.observeAll()) { value, _ -> value }

    /**
     * Resolves category name/icon/color from the categories table
     * for a mapped Transaction whose categoryId is set.
     */
    private suspend fun enrichWithCategory(transaction: Transaction): Transaction {
        val category = transaction.categoryId?.let { categoryDao.getById(it) }
            ?: return transaction
        return transaction.copy(
            categoryName = category.name,
            categoryIcon = category.icon,
            categoryColor = category.color
        )
    }

    override suspend fun update(transaction: Transaction): Result<Unit> = runCatching {
        transactionDao.updateMonotonic(
            mapper.toEntity(
                transaction.copy(
                    syncStatus = if (transaction.syncStatus == SyncStatus.SYNCED) {
                        SyncStatus.PENDING_SYNC
                    } else {
                        transaction.syncStatus
                    }
                )
            )
        )
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
        transactionDao.softDeleteById(id, System.currentTimeMillis())
    }

    override suspend fun search(keyword: String): List<Transaction> =
        transactionDao.search(keyword).map { enrichWithCategory(mapper.toDomain(it)) }

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
        ).withCategoryChanges().map { sums ->
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
        transactionDao.getPendingSyncTransactions().map { enrichWithCategory(mapper.toDomain(it)) }

    override suspend fun markSynced(ids: List<Long>) {
        ids.forEach { transactionDao.updateSyncStatus(it, SyncStatus.SYNCED.name) }
    }

    override suspend fun acknowledgeSynced(
        syncId: String,
        expectedUpdatedAt: LocalDateTime,
        expectedServerVersion: Long,
        serverVersion: Long,
        recordedByUserId: String?,
        recordedByDisplayName: String?,
        recordedByEmail: String?
    ): Boolean = transactionDao.acknowledgeSync(
        syncId = syncId,
        expectedUpdatedAt = expectedUpdatedAt.toEpochMillis(),
        expectedServerVersion = expectedServerVersion,
        serverVersion = serverVersion,
        recordedByUserId = recordedByUserId,
        recordedByDisplayName = recordedByDisplayName,
        recordedByEmail = recordedByEmail
    ) == 1

    override suspend fun rebasePendingSync(
        syncId: String,
        expectedServerVersion: Long,
        serverVersion: Long
    ): Boolean = transactionDao.rebasePendingSync(
        syncId = syncId,
        expectedServerVersion = expectedServerVersion,
        serverVersion = serverVersion
    ) == 1

    override suspend fun mergeRemote(transaction: Transaction): Boolean {
        val localCategoryId = transaction.categoryName
            ?.takeIf { it.isNotBlank() }
            ?.let {
                categoryDao.resolveRemoteCategory(
                    it, transaction.type.name, transaction.categoryIcon, transaction.categoryColor
                )
            }
        return transactionDao.mergeRemote(
            mapper.toEntity(
                transaction.copy(
                    id = 0,
                    categoryId = localCategoryId,
                    syncStatus = SyncStatus.SYNCED
                )
            )
        )
    }

    override suspend fun needsRecordedByMetadataRefresh(): Boolean =
        transactionDao.hasSyncedTransactionsMissingRecordedBy()

    override suspend fun refreshRecordedByMetadata(transaction: Transaction): Boolean =
        transactionDao.refreshRecordedByMetadata(
            syncId = transaction.syncId,
            recordedByUserId = transaction.recordedByUserId,
            recordedByDisplayName = transaction.recordedByDisplayName,
            recordedByEmail = transaction.recordedByEmail
        ) == 1

    override suspend fun getMonthlyExpense(yearMonth: YearMonth): Double =
        transactionDao.sumByTypeAndDateRange(
            "EXPENSE", yearMonth.startOfMonthMillis(), yearMonth.endOfMonthMillis()
        )

    override suspend fun getCategoryBreakdownOnce(type: String, yearMonth: YearMonth): List<CategoryExpense> {
        val sums = transactionDao.getCategoryBreakdown(type, yearMonth.startOfMonthMillis(), yearMonth.endOfMonthMillis())
        val total = sums.sumOf { it.total }
        return sums.mapNotNull { sum ->
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
}
