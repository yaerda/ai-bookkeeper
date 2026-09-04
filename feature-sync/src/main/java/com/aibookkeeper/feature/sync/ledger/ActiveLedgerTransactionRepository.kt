package com.aibookkeeper.feature.sync.ledger

import com.aibookkeeper.core.data.di.LocalLedger
import com.aibookkeeper.core.data.model.CategoryExpense
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.repository.TransactionMonthSummary
import com.aibookkeeper.core.data.repository.TransactionRepository
import java.time.LocalDateTime
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ActiveLedgerTransactionRepository @Inject constructor(
    @LocalLedger private val localRepository: TransactionRepository,
    private val session: SharedLedgerSession
) : TransactionRepository {

    override suspend fun create(transaction: Transaction): Result<Long> =
        if (isLocal()) {
            localRepository.create(transaction)
        } else {
            runCatching { session.push(transaction).id }
        }

    override suspend fun getById(id: Long): Transaction? =
        if (isLocal()) localRepository.getById(id)
        else session.remoteTransactions.value.firstOrNull { it.id == id }

    override fun observeById(id: Long): Flow<Transaction?> =
        selectedFlow(
            local = { localRepository.observeById(id) },
            remote = { transactions -> transactions.firstOrNull { it.id == id } }
        )

    override fun observeByDateRange(
        start: LocalDateTime,
        end: LocalDateTime
    ): Flow<List<Transaction>> = selectedFlow(
        local = { localRepository.observeByDateRange(start, end) },
        remote = { transactions ->
            transactions.filter { it.date >= start && it.date < end }
        }
    )

    override fun observeByMonth(yearMonth: YearMonth): Flow<List<Transaction>> =
        selectedFlow(
            local = { localRepository.observeByMonth(yearMonth) },
            remote = { transactions ->
                transactions.filter { YearMonth.from(it.date) == yearMonth }
            }
        )

    override fun observeTransactionMonths(): Flow<List<TransactionMonthSummary>> =
        selectedFlow(
            local = localRepository::observeTransactionMonths,
            remote = { transactions ->
                transactions.groupingBy { YearMonth.from(it.date) }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.key }
                    .map { TransactionMonthSummary(it.key, it.value) }
            }
        )

    override fun observePendingTransactions(): Flow<List<Transaction>> =
        localRepository.observePendingTransactions()

    override fun observePendingSyncCount(): Flow<Int> =
        localRepository.observePendingSyncCount()

    override fun observeByCategoryAndMonth(
        categoryId: Long,
        yearMonth: YearMonth
    ): Flow<List<Transaction>> = selectedFlow(
        local = {
            localRepository.observeByCategoryAndMonth(categoryId, yearMonth)
        },
        remote = { transactions ->
            transactions.filter {
                YearMonth.from(it.date) == yearMonth &&
                    categoryKey(it) == categoryId
            }
        }
    )

    override suspend fun update(transaction: Transaction): Result<Unit> =
        if (isLocal()) {
            localRepository.update(transaction)
        } else {
            runCatching {
                session.push(transaction.copy(updatedAt = LocalDateTime.now()))
                Unit
            }
        }

    override suspend fun confirmTransaction(id: Long): Result<Unit> {
        if (isLocal()) return localRepository.confirmTransaction(id)
        val transaction = getById(id) ?: return Result.failure(
            IllegalArgumentException("账单不存在")
        )
        return update(transaction.copy(status = TransactionStatus.CONFIRMED))
    }

    override suspend fun confirmAll(ids: List<Long>): Result<Unit> = runCatching {
        ids.forEach { confirmTransaction(it).getOrThrow() }
    }

    override suspend fun delete(id: Long): Result<Unit> {
        if (isLocal()) return localRepository.delete(id)
        val transaction = getById(id) ?: return Result.failure(
            IllegalArgumentException("账单不存在")
        )
        return runCatching {
            session.push(
                transaction.copy(
                    deletedAt = LocalDateTime.now(),
                    updatedAt = LocalDateTime.now()
                )
            )
            Unit
        }
    }

    override suspend fun search(keyword: String): List<Transaction> =
        if (isLocal()) {
            localRepository.search(keyword)
        } else {
            session.remoteTransactions.value.filter { transaction ->
                listOf(
                    transaction.categoryName,
                    transaction.merchantName,
                    transaction.note,
                    transaction.originalInput
                ).any { it?.contains(keyword, ignoreCase = true) == true }
            }
        }

    override fun observeMonthlyIncome(yearMonth: YearMonth): Flow<Double> =
        observeByMonth(yearMonth).map { transactions ->
            transactions.filter { it.type == TransactionType.INCOME }.sumOf(Transaction::amount)
        }

    override fun observeMonthlyExpense(yearMonth: YearMonth): Flow<Double> =
        observeByMonth(yearMonth).map { transactions ->
            transactions.filter { it.type == TransactionType.EXPENSE }.sumOf(Transaction::amount)
        }

    override fun observeExpenseBreakdown(yearMonth: YearMonth): Flow<List<CategoryExpense>> =
        session.state.flatMapLatest { state ->
            if (state.selectedLedger.isLocal) {
                localRepository.observeExpenseBreakdown(yearMonth)
            } else {
                remoteExpenseBreakdown(yearMonth)
            }
        }

    override suspend fun getPendingSync(): List<Transaction> =
        localRepository.getPendingSync()

    override suspend fun markSynced(ids: List<Long>) =
        localRepository.markSynced(ids)

    override suspend fun acknowledgeSynced(
        syncId: String,
        expectedUpdatedAt: LocalDateTime,
        expectedServerVersion: Long,
        serverVersion: Long
    ): Boolean = localRepository.acknowledgeSynced(
        syncId,
        expectedUpdatedAt,
        expectedServerVersion,
        serverVersion
    )

    override suspend fun rebasePendingSync(
        syncId: String,
        expectedServerVersion: Long,
        serverVersion: Long
    ): Boolean = localRepository.rebasePendingSync(
        syncId,
        expectedServerVersion,
        serverVersion
    )

    override suspend fun mergeRemote(transaction: Transaction): Boolean =
        localRepository.mergeRemote(transaction)

    override suspend fun getMonthlyExpense(yearMonth: YearMonth): Double =
        if (isLocal()) {
            localRepository.getMonthlyExpense(yearMonth)
        } else {
            session.remoteTransactions.value
                .filter {
                    it.type == TransactionType.EXPENSE &&
                        YearMonth.from(it.date) == yearMonth
                }
                .sumOf(Transaction::amount)
        }

    override suspend fun getCategoryBreakdownOnce(
        type: String,
        yearMonth: YearMonth
    ): List<CategoryExpense> =
        if (isLocal()) {
            localRepository.getCategoryBreakdownOnce(type, yearMonth)
        } else {
            expenseBreakdown(
                session.remoteTransactions.value.filter {
                    it.type.name == type && YearMonth.from(it.date) == yearMonth
                }
            )
        }

    private fun isLocal(): Boolean = session.state.value.selectedLedger.isLocal

    private fun remoteExpenseBreakdown(yearMonth: YearMonth): Flow<List<CategoryExpense>> =
        session.remoteTransactions.map { transactions ->
            expenseBreakdown(
                transactions.filter {
                    it.type == TransactionType.EXPENSE &&
                        YearMonth.from(it.date) == yearMonth
                }
            )
        }

    private fun expenseBreakdown(transactions: List<Transaction>): List<CategoryExpense> {
        val grouped = transactions.groupBy {
            categoryKey(it) to (it.categoryName ?: "其他")
        }
        val total = transactions.sumOf(Transaction::amount)
        return grouped.map { (category, items) ->
            val amount = items.sumOf(Transaction::amount)
            CategoryExpense(
                categoryId = category.first,
                categoryName = category.second,
                categoryColor = items.firstOrNull()?.categoryColor ?: "#607D8B",
                amount = amount,
                percentage = if (total > 0) (amount / total).toFloat() else 0f
            )
        }.sortedByDescending(CategoryExpense::amount)
    }

    private fun categoryKey(transaction: Transaction): Long =
        transaction.categoryId
            ?: transaction.categoryName
                ?.hashCode()
                ?.toLong()
                ?.let { if (it > 0) -it else it }
            ?: 0L

    private fun <T> selectedFlow(
        local: () -> Flow<T>,
        remote: (List<Transaction>) -> T
    ): Flow<T> = session.state.flatMapLatest { state ->
        if (state.selectedLedger.isLocal) {
            local()
        } else {
            session.remoteTransactions.map(remote)
        }
    }
}
