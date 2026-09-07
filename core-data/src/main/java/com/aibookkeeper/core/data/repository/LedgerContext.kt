package com.aibookkeeper.core.data.repository

import kotlinx.coroutines.flow.StateFlow

fun familyIdentityLabel(displayName: String?, email: String): String =
    displayName?.trim()?.takeIf { it.isNotEmpty() } ?: email

data class LedgerOption(
    val id: String,
    val name: String,
    val ownerEmail: String,
    val role: String,
    val mode: String,
    val isLocal: Boolean,
    val ownerDisplayName: String? = null
) {
    val ownerLabel: String
        get() = familyIdentityLabel(ownerDisplayName, ownerEmail)

    val canEdit: Boolean
        get() = role == "OWNER" || role == "EDITOR"
}

data class LedgerContextState(
    val isSignedIn: Boolean = false,
    val ledgers: List<LedgerOption> = listOf(localLedgerOption()),
    val selectedLedgerId: String = LOCAL_LEDGER_ID,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val selectionVersion: Long = 0,
    val localCategoriesSynced: Boolean = false,
    val accountId: String? = null
) {
    val selectedLedger: LedgerOption
        get() = ledgers.firstOrNull { it.id == selectedLedgerId }
            ?: ledgers.firstOrNull { it.isLocal }
            ?: localLedgerOption()

    val selection: LedgerSelection
        get() = LedgerSelection(selectedLedgerId, selectionVersion)

    val canEdit: Boolean
        get() = selectedLedger.canEdit &&
            (selectedLedger.isLocal || (isSignedIn && !isLoading && errorMessage == null))

    val canUpdateCategories: Boolean
        get() = canEdit && selectedLedger.isLocal && !isSignedIn && !localCategoriesSynced
}

data class LedgerSelection(val ledgerId: String, val version: Long)

class LedgerSelectionChangedException :
    IllegalStateException("账本已切换，请重新选择分类")

fun LedgerContext.requireEditable(selection: LedgerSelection = state.value.selection) {
    val current = state.value
    if (current.selection != selection) throw LedgerSelectionChangedException()
    check(current.selectedLedger.canEdit) { "你只有查看权限" }
    check(current.canEdit) { current.errorMessage ?: "账本正在加载，请稍后重试" }
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
