package com.aibookkeeper.feature.sync.ledger

import com.aibookkeeper.core.data.di.LocalLedger
import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.TransactionType
import com.aibookkeeper.core.data.repository.CategoryRepository
import com.aibookkeeper.core.data.repository.LedgerSelectionChangedException
import com.aibookkeeper.core.data.repository.requireEditable
import com.aibookkeeper.feature.sync.queue.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ActiveLedgerCategoryRepository @Inject constructor(
    @LocalLedger private val localRepository: CategoryRepository,
    private val session: SharedLedgerSession,
    private val syncScheduler: SyncScheduler
) : CategoryRepository {

    override fun observeAllCategories(): Flow<List<Category>> =
        selectedFlow(localRepository::observeAllCategories) { it }

    override fun observeExpenseCategories(): Flow<List<Category>> =
        selectedFlow(localRepository::observeExpenseCategories) { categories ->
            categories.filter { it.type == TransactionType.EXPENSE }
        }

    override fun observeIncomeCategories(): Flow<List<Category>> =
        selectedFlow(localRepository::observeIncomeCategories) { categories ->
            categories.filter { it.type == TransactionType.INCOME }
        }

    override fun observeSubCategories(parentId: Long): Flow<List<Category>> =
        selectedFlow({ localRepository.observeSubCategories(parentId) }) { emptyList() }

    override suspend fun getById(id: Long): Category? = read(
        local = { localRepository.getById(id) },
        remote = { it.firstOrNull { category -> category.id == id } }
    )

    override suspend fun findByNameAndType(name: String, type: TransactionType): Category? = read(
        local = { localRepository.findByNameAndType(name, type) },
        remote = { it.findCategory(name, type) }
    )

    override suspend fun create(category: Category): Result<Long> = categoryResult {
        val state = session.state.value
        session.requireEditable(state.selection)
        if (state.selectedLedger.isLocal) {
            val request = category.toCreateRequest()
            session.requireEditable(state.selection)
            localRepository.create(category.copy(name = request.name, icon = request.icon))
                .getOrThrow()
                .also { syncScheduler.onLocalCategoryCreated() }
        } else {
            session.createCategory(category)
        }
    }

    override suspend fun update(category: Category): Result<Unit> = categoryResult {
        session.requireEditable()
        check(session.canUpdateLocalCategories) { "已接入云同步的分类暂不支持修改，可新增分类" }
        localRepository.update(category).getOrThrow()
    }

    override suspend fun delete(id: Long): Result<Unit> = categoryResult {
        session.requireEditable()
        check(session.canUpdateLocalCategories) { "已接入云同步的分类暂不支持删除" }
        localRepository.delete(id).getOrThrow()
    }

    private suspend fun read(
        local: suspend () -> Category?,
        remote: (List<Category>) -> Category?
    ): Category? {
        val state = session.state.value
        val result = if (state.selectedLedger.isLocal) local() else {
            check(state.isSignedIn && !state.isLoading && state.errorMessage == null) {
                state.errorMessage ?: "账本分类正在加载，请稍后重试"
            }
            remote(session.remoteCategories.value)
        }
        if (session.state.value.selection != state.selection) throw LedgerSelectionChangedException()
        return result
    }

    private fun selectedFlow(
        local: () -> Flow<List<Category>>,
        remote: (List<Category>) -> List<Category>
    ): Flow<List<Category>> = session.state.flatMapLatest { state ->
        when {
            state.selectedLedger.isLocal -> local()
            !state.isSignedIn || state.isLoading || state.errorMessage != null -> flowOf(emptyList())
            else -> session.remoteCategories.map(remote)
        }
    }
}

private suspend inline fun <T> categoryResult(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
