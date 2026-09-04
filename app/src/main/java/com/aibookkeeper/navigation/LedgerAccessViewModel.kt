package com.aibookkeeper.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibookkeeper.feature.sync.auth.AuthManager
import com.aibookkeeper.feature.sync.auth.AuthState
import com.aibookkeeper.feature.sync.queue.SyncPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LedgerAccessState {
    data object Loading : LedgerAccessState
    data object Allowed : LedgerAccessState
    data class AccountMismatch(val email: String) : LedgerAccessState
}

@HiltViewModel
class LedgerAccessViewModel @Inject constructor(
    private val authManager: AuthManager,
    syncPreferences: SyncPreferences
) : ViewModel() {

    val accessState: StateFlow<LedgerAccessState> = combine(
        authManager.state,
        syncPreferences.boundAccountId
    ) { authState, boundAccountId ->
        when {
            authState is AuthState.Loading -> LedgerAccessState.Loading
            authState is AuthState.SignedIn &&
                boundAccountId != null &&
                boundAccountId != authState.accountId ->
                LedgerAccessState.AccountMismatch(authState.email)
            else -> LedgerAccessState.Allowed
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LedgerAccessState.Loading
    )

    fun signOut() {
        viewModelScope.launch {
            authManager.signOut()
        }
    }
}
