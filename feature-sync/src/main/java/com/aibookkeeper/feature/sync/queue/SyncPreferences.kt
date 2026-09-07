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

    fun isRecordedByMetadataRefreshComplete(accountId: String): Boolean =
        preferences.getBoolean("$KEY_RECORDED_BY_REFRESH_PREFIX$accountId", false)

    fun markRecordedByMetadataRefreshComplete(accountId: String) {
        preferences.edit().putBoolean("$KEY_RECORDED_BY_REFRESH_PREFIX$accountId", true).apply()
    }

    fun isProjectMetadataRefreshComplete(accountId: String): Boolean =
        preferences.getBoolean("$KEY_PROJECT_REFRESH_PREFIX$accountId", false)

    fun markProjectMetadataRefreshComplete(accountId: String) {
        preferences.edit().putBoolean("$KEY_PROJECT_REFRESH_PREFIX$accountId", true).apply()
    }

    fun projectCacheJson(accountId: String, ledgerId: String): String? =
        preferences.getString("$KEY_PROJECT_CACHE_PREFIX$accountId:$ledgerId", null)

    fun projectCacheRefreshedAt(accountId: String, ledgerId: String): Long? =
        preferences.takeIf { it.contains("$KEY_PROJECT_CACHE_REFRESH_PREFIX$accountId:$ledgerId") }
            ?.getLong("$KEY_PROJECT_CACHE_REFRESH_PREFIX$accountId:$ledgerId", 0L)

    fun updateProjectCache(accountId: String, ledgerId: String, json: String, refreshedAt: Long) {
        preferences.edit()
            .putString("$KEY_PROJECT_CACHE_PREFIX$accountId:$ledgerId", json)
            .putLong("$KEY_PROJECT_CACHE_REFRESH_PREFIX$accountId:$ledgerId", refreshedAt)
            .apply()
    }

    private companion object {
        const val KEY_ACCOUNT_ID = "bound_account_id"
        const val KEY_CURSOR = "pull_cursor"
        const val KEY_SELECTED_LEDGER_PREFIX = "selected_ledger_id:"
        const val KEY_RECORDED_BY_REFRESH_PREFIX = "recorded_by_refresh_complete:"
        const val KEY_PROJECT_REFRESH_PREFIX = "project_metadata_refresh_complete:"
        const val KEY_PROJECT_CACHE_PREFIX = "project_cache:"
        const val KEY_PROJECT_CACHE_REFRESH_PREFIX = "project_cache_refreshed_at:"
    }
}
