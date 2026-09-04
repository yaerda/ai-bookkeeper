package com.aibookkeeper.feature.sync.ledger

import com.aibookkeeper.core.data.model.Transaction
import com.aibookkeeper.core.data.repository.LOCAL_LEDGER_ID
import com.aibookkeeper.core.data.repository.LedgerContext
import com.aibookkeeper.core.data.repository.LedgerContextState
import com.aibookkeeper.core.data.repository.LedgerOption
import com.aibookkeeper.core.data.repository.localLedgerOption
import com.aibookkeeper.feature.sync.auth.AuthManager
import com.aibookkeeper.feature.sync.auth.AuthState
import com.aibookkeeper.feature.sync.network.PushRequest
import com.aibookkeeper.feature.sync.network.SyncApi
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
class SharedLedgerSession @Inject constructor(
    private val authManager: AuthManager,
    private val api: SyncApi
) : LedgerContext {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(LedgerContextState())
    override val state: StateFlow<LedgerContextState> = _state.asStateFlow()

    private val _remoteTransactions = MutableStateFlow<List<Transaction>>(emptyList())
    val remoteTransactions: StateFlow<List<Transaction>> = _remoteTransactions.asStateFlow()

    init {
        scope.launch {
            authManager.state.collectLatest { authState ->
                when (authState) {
                    is AuthState.SignedIn -> runCatching { refresh() }
                        .onFailure { error ->
                            if (error is CancellationException) throw error
                            _state.value = _state.value.copy(
                                isSignedIn = true,
                                isLoading = false,
                                errorMessage = error.message
                            )
                        }
                    AuthState.Loading -> _state.value = _state.value.copy(isLoading = true)
                    AuthState.SignedOut, is AuthState.Error -> reset()
                }
            }
        }
    }

    override suspend fun refresh() {
        _state.value = _state.value.copy(
            isSignedIn = true,
            isLoading = true,
            errorMessage = null
        )
        val authorization = authorization()
        val response = api.familyLedgers(authorization)
        if (!response.isSuccessful) {
            throw IOException("读取账本失败（HTTP ${response.code()}）")
        }
        val payload = requireNotNull(response.body())
        val remoteOptions = payload.ledgers.map { ledger ->
            val isLocal = ledger.role == "OWNER" && ledger.isDefault
            LedgerOption(
                id = ledger.id,
                name = when {
                    isLocal && ledger.mode == "PERSONAL" -> "个人账本"
                    else -> ledger.name
                },
                ownerEmail = ledger.ownerEmail,
                role = ledger.role,
                mode = ledger.mode,
                isLocal = isLocal
            )
        }
        val options = if (remoteOptions.any { it.isLocal }) {
            remoteOptions
        } else {
            listOf(localLedgerOption()) + remoteOptions
        }
        val previousId = _state.value.selectedLedgerId
        val selectedId = options.firstOrNull { it.id == previousId }?.id
            ?: if (previousId == LOCAL_LEDGER_ID) {
                options.firstOrNull { it.isLocal }?.id
            } else {
                null
            }
            ?: options.first().id
        _state.value = LedgerContextState(
            isSignedIn = true,
            ledgers = options,
            selectedLedgerId = selectedId,
            isLoading = false
        )
        loadSelectedRemoteLedger(authorization)
    }

    override fun selectLedger(ledgerId: String) {
        val ledger = _state.value.ledgers.firstOrNull { it.id == ledgerId } ?: return
        _state.value = _state.value.copy(
            selectedLedgerId = ledger.id,
            isLoading = !ledger.isLocal,
            errorMessage = null
        )
        if (ledger.isLocal) {
            _remoteTransactions.value = emptyList()
        } else {
            scope.launch {
                runCatching { loadSelectedRemoteLedger(authorization()) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        _state.value = _state.value.copy(
                            isLoading = false,
                            errorMessage = error.message
                        )
                    }
            }
        }
    }

    suspend fun push(transaction: Transaction): Transaction {
        val ledger = _state.value.selectedLedger
        check(!ledger.isLocal) { "个人账本应保存到本地" }
        check(ledger.canEdit) { "你只有查看权限" }
        val response = api.push(
            authorization = authorization(),
            request = PushRequest(listOf(transaction.toSyncDto())),
            ledgerId = ledger.id
        )
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
        val saved = accepted.toDomainTransaction()
        _remoteTransactions.value = _remoteTransactions.value
            .filterNot { it.syncId == saved.syncId }
            .let { existing ->
                if (saved.deletedAt == null) existing + saved else existing
            }
            .sortedByDescending(Transaction::date)
        return saved
    }

    private suspend fun loadSelectedRemoteLedger(authorization: String) {
        val selected = _state.value.selectedLedger
        if (selected.isLocal) {
            _remoteTransactions.value = emptyList()
            _state.value = _state.value.copy(isLoading = false)
            return
        }
        val ledgerId = selected.id
        val transactions = mutableListOf<Transaction>()
        var cursor = 0L
        do {
            val response = api.pull(
                authorization = authorization,
                cursor = cursor,
                limit = 500,
                ledgerId = ledgerId
            )
            if (!response.isSuccessful) {
                throw IOException("读取共享账单失败（HTTP ${response.code()}）")
            }
            val page = requireNotNull(response.body())
            transactions += page.transactions
                .map { it.toDomainTransaction() }
                .filter { it.deletedAt == null }
            cursor = page.nextCursor
        } while (page.hasMore)
        if (_state.value.selectedLedgerId == ledgerId) {
            _remoteTransactions.value = transactions.sortedByDescending(Transaction::date)
            _state.value = _state.value.copy(isLoading = false)
        }
    }

    private suspend fun authorization(): String {
        val token = authManager.acquireToken()
            ?: throw IllegalStateException("请先登录")
        return "Bearer ${token.value}"
    }

    private fun reset() {
        _remoteTransactions.value = emptyList()
        _state.value = LedgerContextState()
    }
}
