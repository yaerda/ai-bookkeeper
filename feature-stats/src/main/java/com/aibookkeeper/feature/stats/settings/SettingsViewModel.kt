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

    private val prefs = context.getSharedPreferences("azure_openai", Context.MODE_PRIVATE)

    init {
        refreshState()
    }

    /** Re-read permission + preference state (call after returning from system settings). */
    fun refreshState() {
        _uiState.update {
            it.copy(
                isPermissionGranted = NotificationPermissionHelper.isPermissionGranted(context),
                isNotificationEnabled = NotificationPermissionHelper.isNotificationEnabled(context),
                azureEndpoint = prefs.getString("endpoint", "") ?: "",
                azureApiKey = prefs.getString("api_key", "") ?: "",
                azureDeployment = prefs.getString("deployment", "gpt-4.1-mini") ?: "gpt-4.1-mini"
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

    fun setAzureEndpoint(value: String) {
        prefs.edit().putString("endpoint", value).apply()
        _uiState.update { it.copy(azureEndpoint = value) }
    }

    fun setAzureApiKey(value: String) {
        prefs.edit().putString("api_key", value).apply()
        _uiState.update { it.copy(azureApiKey = value) }
    }

    fun setAzureDeployment(value: String) {
        prefs.edit().putString("deployment", value).apply()
        _uiState.update { it.copy(azureDeployment = value) }
    }
}

data class SettingsUiState(
    val isPermissionGranted: Boolean = false,
    val isNotificationEnabled: Boolean = false,
    val azureEndpoint: String = "",
    val azureApiKey: String = "",
    val azureDeployment: String = "gpt-4.1-mini"
)
