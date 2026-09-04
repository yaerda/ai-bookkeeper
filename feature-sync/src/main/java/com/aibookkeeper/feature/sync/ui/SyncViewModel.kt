package com.aibookkeeper.feature.sync.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.feature.sync.auth.AuthManager
import com.aibookkeeper.feature.sync.auth.AuthState
import com.aibookkeeper.feature.sync.queue.SyncManager
import com.aibookkeeper.feature.sync.queue.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val syncManager: SyncManager
) : ViewModel() {

    val authState: StateFlow<AuthState> = authManager.state
    val syncState: Flow<SyncState> = syncManager.observeSyncState()
    val pendingCount: Flow<Int> = syncManager.observePendingCount()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun signIn(activity: Activity) {
        viewModelScope.launch {
            authManager.signIn(activity)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authManager.signOut()
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _message.value = null
            syncManager.syncNow()
                .onSuccess { report ->
                    if (report.failed > 0) {
                        _message.value =
                            "${report.failed} 笔数据被服务器拒绝（${report.failedSyncIds.first()}），其他账单已继续同步"
                    } else if (report.conflicts > 0) {
                        _message.value = "仍有 ${report.conflicts} 笔冲突，将自动重试"
                    }
                }
                .onFailure { error ->
                    _message.value = error.message ?: "同步失败，请重试"
                }
        }
    }
}
