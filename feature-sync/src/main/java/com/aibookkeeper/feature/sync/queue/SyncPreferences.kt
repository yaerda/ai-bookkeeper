package com.aibookkeeper.feature.sync.queue

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences =
        context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE)
    private val _boundAccountId =
        MutableStateFlow(preferences.getString(KEY_ACCOUNT_ID, null))
    val boundAccountId: StateFlow<String?> = _boundAccountId.asStateFlow()

    @Synchronized
    fun bindAccount(accountId: String): Boolean {
        val boundAccount = _boundAccountId.value
        if (boundAccount != null) {
            return boundAccount == accountId
        }
        preferences.edit().putString(KEY_ACCOUNT_ID, accountId).apply()
        _boundAccountId.value = accountId
        return true
    }

    fun cursor(): Long = preferences.getLong(KEY_CURSOR, 0)

    fun updateCursor(cursor: Long) {
        preferences.edit().putLong(KEY_CURSOR, cursor).apply()
    }

    fun selectedLedgerId(accountId: String): String? =
        preferences.getString("$KEY_SELECTED_LEDGER_PREFIX$accountId", null)

    fun updateSelectedLedgerId(accountId: String, ledgerId: String) {
        preferences.edit().putString("$KEY_SELECTED_LEDGER_PREFIX$accountId", ledgerId).apply()
    }

    private companion object {
        const val KEY_ACCOUNT_ID = "bound_account_id"
        const val KEY_CURSOR = "pull_cursor"
        const val KEY_SELECTED_LEDGER_PREFIX = "selected_ledger_id:"
    }
}
