package com.aibookkeeper.core.data.repository

import kotlinx.coroutines.flow.StateFlow

data class LedgerOption(
    val id: String,
    val name: String,
    val ownerEmail: String,
    val role: String,
    val mode: String,
    val isLocal: Boolean
) {
    val canEdit: Boolean
        get() = role == "OWNER" || role == "EDITOR"
}

data class LedgerContextState(
    val isSignedIn: Boolean = false,
    val ledgers: List<LedgerOption> = listOf(localLedgerOption()),
    val selectedLedgerId: String = LOCAL_LEDGER_ID,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val selectedLedger: LedgerOption
        get() = ledgers.firstOrNull { it.id == selectedLedgerId }
            ?: ledgers.firstOrNull { it.isLocal }
            ?: localLedgerOption()
}

interface LedgerContext {
    val state: StateFlow<LedgerContextState>

    fun selectLedger(ledgerId: String)

    suspend fun refresh()
}

const val LOCAL_LEDGER_ID = "local-personal-ledger"

fun localLedgerOption() = LedgerOption(
    id = LOCAL_LEDGER_ID,
    name = "个人账本",
    ownerEmail = "",
    role = "OWNER",
    mode = "PERSONAL",
    isLocal = true
)
