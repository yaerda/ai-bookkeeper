package com.aibookkeeper.feature.sync.ledger

import com.aibookkeeper.core.data.model.Category
import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.model.newestTransactionFirst
import com.aibookkeeper.core.data.repository.LOCAL_LEDGER_ID
import com.aibookkeeper.core.data.repository.LedgerContext
import com.aibookkeeper.core.data.repository.LedgerContextState
import com.aibookkeeper.core.data.repository.LedgerOption
import com.aibookkeeper.core.data.repository.LedgerSelectionChangedException
import com.aibookkeeper.core.data.repository.localLedgerOption
import com.aibookkeeper.core.data.repository.requireEditable
import com.aibookkeeper.feature.sync.auth.AccessToken
import com.aibookkeeper.feature.sync.auth.AuthManager
import com.aibookkeeper.feature.sync.auth.AuthState
import com.aibookkeeper.feature.sync.network.PushRequest
import com.aibookkeeper.feature.sync.network.SyncApi
import com.aibookkeeper.feature.sync.queue.LedgerCategorySync
import com.aibookkeeper.feature.sync.queue.SyncPreferences
import com.aibookkeeper.feature.sync.queue.toDomainTransaction
import com.aibookkeeper.feature.sync.queue.toSyncDto
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Singleton
class SharedLedgerSession internal constructor(
    private val authManager: AuthManager,
    private val api: SyncApi,
    private val categorySync: LedgerCategorySync,
    private val preferences: SyncPreferences,
    private val scope: CoroutineScope
) : LedgerContext {

    @Inject
    constructor(
        authManager: AuthManager,
        api: SyncApi,
        categorySync: LedgerCategorySync,
        preferences: SyncPreferences
    ) : this(
        authManager, api, categorySync, preferences,
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private val lock = Any()
    private var generation = 0L
    private var accountId: String? = null
    private val _state = MutableStateFlow(
        LedgerContextState(localCategoriesSynced = preferences.boundAccountId.value != null)
    )
    override val state: StateFlow<LedgerContextState> = _state.asStateFlow()

    private val _remoteTransactions = MutableStateFlow<List<Transaction>>(emptyList())
    val remoteTransactions: StateFlow<List<Transaction>> = _remoteTransactions.asStateFlow()
    private val _remoteCategories = MutableStateFlow<List<Category>>(emptyList())
    val remoteCategories: StateFlow<List<Category>> = _remoteCategories.asStateFlow()

    val canUpdateLocalCategories: Boolean
        get() = state.value.canUpdateCategories && preferences.boundAccountId.value == null

    init {
        scope.launch {
            preferences.boundAccountId.collectLatest { bound ->
                synchronized(lock) {
                    _state.value = _state.value.copy(localCategoriesSynced = bound != null)
                }
            }
        }
        scope.launch {
            authManager.state.collectLatest { authState ->
                when (authState) {
                    is AuthState.SignedIn -> runCatching { refresh() }.onFailure {
                        if (it is CancellationException) throw it
                    }
                    AuthState.Loading -> reset(isLoading = true)
                    AuthState.SignedOut, is AuthState.Error -> reset()
                }
            }
        }
    }

    override suspend fun refresh() {
        val signedIn = authManager.state.value as? AuthState.SignedIn
            ?: return reset()
        val boundAccount = preferences.boundAccountId.value
        val canUseLocal = boundAccount == null || boundAccount == signedIn.accountId
        val request = synchronized(lock) {
            if (accountId != signedIn.accountId) {
                _state.value = LedgerContextState(
                    ledgers = listOf(localLedgerOption().copy(isLocal = canUseLocal)),
                    localCategoriesSynced = boundAccount != null
                )
            }
            accountId = signedIn.accountId
            generation++
            clearRemote()
            _state.value = _state.value.copy(
                isSignedIn = true,
                isLoading = true,
                errorMessage = null,
                selectionVersion = generation
            )
            Request(signedIn.accountId, generation)
        }
        try {
            val token = token(request)
            val response = api.familyLedgers("Bearer ${token.value}")
            if (!isCurrent(request)) return
            if (!response.isSuccessful) {
                throw IOException("读取账本失败（HTTP ${response.code()}）")
            }
            val payload = requireNotNull(response.body())
            val options = payload.ledgers.map { ledger ->
                val isLocal = ledger.role == "OWNER" && ledger.isDefault && canUseLocal
                LedgerOption(
                    id = ledger.id,
                    name = if (isLocal && ledger.mode == "PERSONAL") "个人账本" else ledger.name,
                    ownerEmail = ledger.ownerEmail,
                    ownerDisplayName = ledger.ownerDisplayName,
                    role = ledger.role,
                    mode = ledger.mode,
                    isLocal = isLocal
                )
            }.ifEmpty { listOf(localLedgerOption().copy(isLocal = canUseLocal)) }
            synchronized(lock) {
                if (!isCurrent(request)) return
                val previousId = _state.value.selectedLedgerId
                    .takeUnless { it == LOCAL_LEDGER_ID }
                    ?: preferences.selectedLedgerId(request.accountId)
                val selectedId = options.firstOrNull { it.id == previousId }?.id
                    ?: options.firstOrNull { it.isLocal }?.id
                    ?: options.first().id
                _state.value = _state.value.copy(
                    ledgers = options,
                    selectedLedgerId = selectedId,
                    isLoading = !options.first { it.id == selectedId }.isLocal
                )
                preferences.updateSelectedLedgerId(request.accountId, selectedId)
            }
            loadSelectedLedger(request, token)
        } catch (error: Exception) {
            reportError(request, error)
            throw error
        }
    }

    override fun selectLedger(ledgerId: String) {
        val request = synchronized(lock) {
            val ledger = _state.value.ledgers.firstOrNull { it.id == ledgerId } ?: return
            generation++
            clearRemote()
            _state.value = _state.value.copy(
                selectedLedgerId = ledger.id,
                isLoading = !ledger.isLocal,
                errorMessage = null,
                selectionVersion = generation
            )
            val account = accountId ?: return
            val selectedRequest = Request(account, generation, ledger.id)
            if (!isCurrent(selectedRequest)) return
            preferences.updateSelectedLedgerId(account, ledger.id)
            selectedRequest
        }
        scope.launch {
            try {
                loadSelectedLedger(request, token(request))
            } catch (error: Exception) {
                reportError(request, error)
                if (error is CancellationException) throw error
            }
        }
    }

    suspend fun createCategory(category: Category): Long {
        val request = writableRemoteRequest()
        val token = token(request)
        val response = api.createCategory(
            authorization = "Bearer ${token.value}",
            request = category.toCreateRequest(),
            ledgerId = request.ledgerId
        )
        ensureCurrent(request)
        if (!response.isSuccessful) {
            throw IOException("新增分类失败（HTTP ${response.code()}）")
        }
        val saved = requireNotNull(response.body()).category.toCategory()
        synchronized(lock) {
            ensureCurrent(request)
            _remoteCategories.value = (_remoteCategories.value.filterNot { it.id == saved.id } + saved)
                .sortedWith(compareBy({ it.type.name }, { it.sortOrder }, { it.name }))
            _remoteTransactions.value = _remoteTransactions.value.map {
                it.withCatalog(_remoteCategories.value)
            }
        }
        return saved.id
    }

    suspend fun push(transaction: Transaction): Transaction {
        val request = writableRemoteRequest()
        val normalized = synchronized(lock) {
            if (transaction.id != 0L) {
                check(_remoteTransactions.value.any { it.syncId == transaction.syncId }) {
                    "账单不属于当前账本，请刷新后重试"
                }
            }
            val resolved = transaction.withCatalog(_remoteCategories.value)
            check(transaction.categoryId == null || transaction.categoryId == resolved.categoryId) {
                "分类已变化，请重新选择"
            }
            resolved
        }
        val token = token(request)
        val response = api.push(
            authorization = "Bearer ${token.value}",
            request = PushRequest(listOf(normalized.toSyncDto())),
            ledgerId = request.ledgerId
        )
        ensureCurrent(request)
        if (!response.isSuccessful) {
            throw IOException("保存账单失败（HTTP ${response.code()}）")
        }
        val body = requireNotNull(response.body())
        val accepted = body.accepted.firstOrNull()
            ?: throw IOException(
                if (body.conflicts.isNotEmpty()) {
                    "账单已被其他成员修改，请刷新后重试"
                } else {
                    "服务器未接受账单"
                }
            )
        return synchronized(lock) {
            ensureCurrent(request)
            val saved = accepted.toDomainTransaction().withCatalog(_remoteCategories.value)
            _remoteTransactions.value = _remoteTransactions.value
                .filterNot { it.syncId == saved.syncId }
                .let { existing -> if (saved.deletedAt == null) existing + saved else existing }
                .sortedWith(newestTransactionFirst)
            saved
        }
    }

    private suspend fun loadSelectedLedger(request: Request, token: AccessToken) {
        ensureCurrent(request)
        val selected = _state.value.selectedLedger
        val selectedRequest = request.copy(ledgerId = selected.id)
        ensureCurrent(selectedRequest)
        if (selected.isLocal) {
            if (selected.id != LOCAL_LEDGER_ID) categorySync.syncDefault(token)
            synchronized(lock) {
                if (isCurrent(selectedRequest)) {
                    _state.value = _state.value.copy(isLoading = false)
                }
            }
            return
        }
        val categoryResponse = api.categories("Bearer ${token.value}", selected.id)
        if (!isCurrent(selectedRequest)) return
        if (!categoryResponse.isSuccessful) {
            throw IOException("读取账本分类失败（HTTP ${categoryResponse.code()}）")
        }
        val categories = requireNotNull(categoryResponse.body()).categories.map { it.toCategory() }
        val transactions = mutableListOf<Transaction>()
        var cursor = 0L
        do {
            val response = api.pull(
                authorization = "Bearer ${token.value}",
                cursor = cursor,
                limit = 500,
                ledgerId = selected.id
            )
            if (!isCurrent(selectedRequest)) return
            if (!response.isSuccessful) {
                throw IOException("读取共享账单失败（HTTP ${response.code()}）")
            }
            val page = requireNotNull(response.body())
            transactions += page.transactions
                .map { it.toDomainTransaction().withCatalog(categories) }
                .filter { it.deletedAt == null }
            if (page.hasMore && page.nextCursor <= cursor) throw IOException("同步游标没有前进")
            cursor = page.nextCursor
        } while (page.hasMore)
        synchronized(lock) {
            if (isCurrent(selectedRequest)) {
                _remoteCategories.value = categories
                    .sortedWith(compareBy({ it.type.name }, { it.sortOrder }, { it.name }))
                _remoteTransactions.value = transactions.sortedWith(newestTransactionFirst)
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    private fun writableRemoteRequest(): Request = synchronized(lock) {
        requireEditable()
        val ledger = _state.value.selectedLedger
        check(!ledger.isLocal) { "个人账本应保存到本地" }
        val account = accountId ?: throw IllegalStateException("请先登录")
        Request(account, generation, ledger.id).also(::ensureCurrent)
    }

    private suspend fun token(request: Request): AccessToken {
        ensureCurrent(request)
        val token = authManager.acquireToken() ?: throw IllegalStateException("请先登录")
        ensureCurrent(request)
        if (token.accountId != request.accountId) throw LedgerSelectionChangedException()
        return token
    }

    private fun isCurrent(request: Request): Boolean = synchronized(lock) {
        request.generation == generation && request.accountId == accountId &&
            (authManager.state.value as? AuthState.SignedIn)?.accountId == request.accountId &&
            (request.ledgerId == null || request.ledgerId == _state.value.selectedLedgerId)
    }

    private fun ensureCurrent(request: Request) {
        if (!isCurrent(request)) throw LedgerSelectionChangedException()
    }

    private fun reportError(request: Request, error: Exception) = synchronized(lock) {
        if (error !is CancellationException && isCurrent(request)) {
            _state.value = _state.value.copy(isLoading = false, errorMessage = error.message)
        }
    }

    private fun clearRemote() {
        _remoteCategories.value = emptyList()
        _remoteTransactions.value = emptyList()
    }

    private fun reset(isLoading: Boolean = false) = synchronized(lock) {
        generation++
        accountId = null
        clearRemote()
        _state.value = LedgerContextState(
            isLoading = isLoading,
            selectionVersion = generation,
            localCategoriesSynced = preferences.boundAccountId.value != null
        )
    }

    private data class Request(
        val accountId: String,
        val generation: Long,
        val ledgerId: String? = null
    )
}
