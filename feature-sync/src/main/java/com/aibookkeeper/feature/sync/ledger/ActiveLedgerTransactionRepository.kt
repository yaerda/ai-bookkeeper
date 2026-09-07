package com.aibookkeeper.feature.sync.ledger

import com.aibookkeeper.core.data.di.LocalLedger
import com.aibookkeeper.core.data.model.CategoryExpense
import com.aibookkeeper.core.data.model.categoryKey
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.TransactionStatus
import com.aibookkeeper.core.data.model.TransactionSource
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.repository.TransactionMonthSummary
import com.aibookkeeper.core.data.repository.ProjectRepository
import com.aibookkeeper.core.data.repository.TransactionRepository
import com.aibookkeeper.core.data.repository.LedgerSelectionChangedException
import com.aibookkeeper.core.data.repository.requireEditable
import java.time.LocalDateTime
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ActiveLedgerTransactionRepository @Inject constructor(
    @LocalLedger private val localRepository: TransactionRepository,
    private val projectRepository: ProjectRepository,
    private val session: SharedLedgerSession
) : TransactionRepository {

    override suspend fun create(transaction: Transaction): Result<Long> =
        resultOf {
            val state = session.state.value
            if (transaction.source != TransactionSource.AUTO_CAPTURE) session.requireEditable(state.selection)
            val destination = transaction.projectDestination
                ?: projectRepository.captureDestination(transaction.source == TransactionSource.AUTO_CAPTURE)
            projectRepository.requireCurrentDestination(destination)
            if (transaction.source != TransactionSource.AUTO_CAPTURE) {
                check(destination.canWrite) { "目标账本或登录账户已变化，请重试" }
            }
            if (transaction.source != TransactionSource.AUTO_CAPTURE &&
                destination.selection != null && destination.selection != state.selection
            ) throw LedgerSelectionChangedException()
            val normalized = transaction.copy(
                projectIds = projectRepository.resolveProjectIds(destination, transaction.projectIds)
            )
            projectRepository.requireCurrentDestination(destination)
            // Capture resolves Room category IDs independently of the selected online ledger.
            if (normalized.source == TransactionSource.AUTO_CAPTURE) {
                return@resultOf localRepository.createValidated(normalized) {
                    projectRepository.requireCurrentDestination(destination)
                }.getOrThrow()
            }
            session.requireEditable(state.selection)
            if (state.selectedLedger.isLocal) localRepository.createValidated(normalized) {
                projectRepository.requireCurrentDestination(destination)
                session.requireEditable(state.selection)
            }.getOrThrow()
            else session.push(normalized, state.selection).id
        }

    override suspend fun getById(id: Long): Transaction? {
        val state = session.state.value
        val transaction = if (state.selectedLedger.isLocal) localRepository.getById(id)
        else remoteSnapshot().firstOrNull { it.id == id }
        if (session.state.value.selection != state.selection) throw LedgerSelectionChangedException()
        return transaction
    }

    override suspend fun createAllValidated(
        transactions: List<Transaction>,
        beforePersist: () -> Unit
    ): Result<List<Long>> = resultOf {
        check(transactions.all { it.source == TransactionSource.AUTO_CAPTURE }) {
            "整批写入仅适用于默认本地账本的图片记账"
        }
        if (transactions.isEmpty()) return@resultOf emptyList()
        val destination = transactions.first().projectDestination
            ?: projectRepository.captureDestination(defaultRoom = true)
        check(destination.defaultRoom && transactions.all {
            it.projectDestination == null || it.projectDestination == destination
        }) { "批量账目的目标账本不一致" }
        projectRepository.requireCurrentDestination(destination)
        val normalized = transactions.map { transaction ->
            transaction.copy(
                projectIds = projectRepository.resolveProjectIds(destination, transaction.projectIds),
                projectDestination = destination
            )
        }
        localRepository.createAllValidated(normalized) {
            projectRepository.requireCurrentDestination(destination)
            beforePersist()
        }.getOrThrow()
    }

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
                    it.categoryKey() == categoryId
            }
        }
    )

    override suspend fun update(transaction: Transaction): Result<Unit> =
        resultOf {
            val state = session.state.value
            session.requireEditable(state.selection)
            val destination = projectRepository.captureDestination()
            check(destination.canWrite) { "目标账本或登录账户已变化，请重试" }
            if (destination.selection != null && destination.selection != state.selection) {
                throw LedgerSelectionChangedException()
            }
            if (transaction.projectIds != null) {
                projectRepository.resolveProjectIds(destination, transaction.projectIds)
            }
            projectRepository.requireCurrentDestination(destination)
            session.requireEditable(state.selection)
            if (state.selectedLedger.isLocal) {
                localRepository.updateValidated(transaction) {
                    projectRepository.requireCurrentDestination(destination)
                    session.requireEditable(state.selection)
                }.getOrThrow()
            } else {
                session.push(transaction.copy(updatedAt = LocalDateTime.now()), state.selection)
                Unit
            }
        }

    override suspend fun confirmTransaction(id: Long): Result<Unit> {
        if (isLocal()) return localRepository.confirmTransaction(id)
        val transaction = getById(id) ?: return Result.failure(
            IllegalArgumentException("账单不存在")
        )
        return update(transaction.copy(status = TransactionStatus.CONFIRMED, projectIds = null))
    }

    override suspend fun confirmAll(ids: List<Long>): Result<Unit> = resultOf {
        ids.forEach { confirmTransaction(it).getOrThrow() }
    }

    override suspend fun delete(id: Long): Result<Unit> {
        if (isLocal()) return localRepository.delete(id)
        if (id > 0 && localRepository.getById(id)?.source == TransactionSource.AUTO_CAPTURE) {
            return localRepository.delete(id)
        }
        val transaction = getById(id) ?: return Result.failure(
            IllegalArgumentException("账单不存在")
        )
        return resultOf {
            session.push(
                transaction.copy(
                    deletedAt = LocalDateTime.now(),
                    updatedAt = LocalDateTime.now(),
                    projectIds = null
                )
            )
            Unit
        }
    }

    override suspend fun search(keyword: String): List<Transaction> =
        if (isLocal()) {
            localRepository.search(keyword)
        } else {
            remoteSnapshot().filter { transaction ->
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
            } else if (!state.isSignedIn || state.isLoading || state.errorMessage != null) {
                flowOf(emptyList())
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
        serverVersion: Long,
        projectIds: List<String>?,
        recordedByUserId: String?,
        recordedByDisplayName: String?,
        recordedByEmail: String?
    ): Boolean = localRepository.acknowledgeSynced(
        syncId,
        expectedUpdatedAt,
        expectedServerVersion,
        serverVersion,
        projectIds,
        recordedByUserId,
        recordedByDisplayName,
        recordedByEmail
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

    override suspend fun needsRecordedByMetadataRefresh(): Boolean =
        localRepository.needsRecordedByMetadataRefresh()

    override suspend fun refreshRecordedByMetadata(transaction: Transaction): Boolean =
        localRepository.refreshRecordedByMetadata(transaction)

    override suspend fun needsProjectMetadataRefresh(): Boolean =
        localRepository.needsProjectMetadataRefresh()

    override suspend fun refreshProjectMetadata(transaction: Transaction): Boolean =
        localRepository.refreshProjectMetadata(transaction)

    override suspend fun getMonthlyExpense(yearMonth: YearMonth): Double =
        if (isLocal()) {
            localRepository.getMonthlyExpense(yearMonth)
        } else {
            remoteSnapshot()
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
                remoteSnapshot().filter {
                    it.type.name == type && YearMonth.from(it.date) == yearMonth
                }
            )
        }

    private fun isLocal(): Boolean = session.state.value.selectedLedger.isLocal

    private fun remoteSnapshot(): List<Transaction> {
        val state = session.state.value
        return if (state.isSignedIn && !state.isLoading && state.errorMessage == null) {
            session.remoteTransactions.value
        } else {
            emptyList()
        }
    }

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
            it.categoryKey() to (it.categoryName ?: "其他")
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

    private suspend fun <T> resultOf(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun <T> selectedFlow(
        local: () -> Flow<T>,
        remote: (List<Transaction>) -> T
    ): Flow<T> = session.state.flatMapLatest { state ->
        if (state.selectedLedger.isLocal) {
            local()
        } else if (!state.isSignedIn || state.isLoading || state.errorMessage != null) {
            flowOf(remote(emptyList()))
        } else {
            session.remoteTransactions.map(remote)
        }
    }
}
