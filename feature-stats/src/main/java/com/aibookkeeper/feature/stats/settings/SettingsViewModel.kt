package com.aibookkeeper.feature.stats.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.aibookkeeper.core.common.permission.NotificationPermissionHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshState()
    }

    /** Re-read permission + preference state (call after returning from system settings). */
    fun refreshState() {
        _uiState.update {
            it.copy(
                isPermissionGranted = NotificationPermissionHelper.isPermissionGranted(context),
                isNotificationEnabled = NotificationPermissionHelper.isNotificationEnabled(context)
            )
        }
    }

    /** Toggle the persistent notification preference. */
    fun setNotificationEnabled(enabled: Boolean) {
        NotificationPermissionHelper.setNotificationEnabled(context, enabled)
        _uiState.update { it.copy(isNotificationEnabled = enabled) }
    }

    /** Called after the runtime permission request completes. */
    fun onPermissionResult(granted: Boolean) {
        NotificationPermissionHelper.markPermissionRequested(context)
        _uiState.update { it.copy(isPermissionGranted = granted) }
        if (granted) {
            setNotificationEnabled(true)
        }
    }
}

data class SettingsUiState(
    val isPermissionGranted: Boolean = false,
    val isNotificationEnabled: Boolean = false
)
