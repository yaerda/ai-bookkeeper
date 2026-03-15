package com.aibookkeeper.feature.stats.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.aibookkeeper.core.common.permission.NotificationPermissionHelper
import com.aibookkeeper.core.data.security.SecureConfigStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureConfigStore: SecureConfigStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val legacyPrefs = context.getSharedPreferences("azure_openai", Context.MODE_PRIVATE)

    init {
        migrateLegacyPrefsIfNeeded()
        refreshState()
    }

    /** Re-read permission + preference state (call after returning from system settings). */
    fun refreshState() {
        _uiState.update {
            it.copy(
                isPermissionGranted = NotificationPermissionHelper.isPermissionGranted(context),
                isNotificationEnabled = NotificationPermissionHelper.isNotificationEnabled(context),
                azureEndpoint = secureConfigStore.getEndpoint(),
                azureApiKey = secureConfigStore.getApiKey(),
                azureDeployment = secureConfigStore.getDeployment().ifBlank { "gpt-4.1-mini" },
                azureSpeechDeployment = secureConfigStore.getSpeechDeployment()
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
        secureConfigStore.setEndpoint(value)
        _uiState.update { it.copy(azureEndpoint = value) }
    }

    fun setAzureApiKey(value: String) {
        secureConfigStore.setApiKey(value)
        _uiState.update { it.copy(azureApiKey = value) }
    }

    fun setAzureDeployment(value: String) {
        secureConfigStore.setDeployment(value)
        _uiState.update { it.copy(azureDeployment = value) }
    }

    fun setAzureSpeechDeployment(value: String) {
        secureConfigStore.setSpeechDeployment(value)
        _uiState.update { it.copy(azureSpeechDeployment = value) }
    }

    private fun migrateLegacyPrefsIfNeeded() {
        val endpoint = legacyPrefs.getString("endpoint", "") ?: ""
        val apiKey = legacyPrefs.getString("api_key", "") ?: ""
        val deployment = legacyPrefs.getString("deployment", "") ?: ""

        if (secureConfigStore.getEndpoint().isBlank() && endpoint.isNotBlank()) {
            secureConfigStore.setEndpoint(endpoint)
        }
        if (secureConfigStore.getApiKey().isBlank() && apiKey.isNotBlank()) {
            secureConfigStore.setApiKey(apiKey)
        }
        if (secureConfigStore.getDeployment().isBlank() && deployment.isNotBlank()) {
            secureConfigStore.setDeployment(deployment)
        }
    }
}

data class SettingsUiState(
    val isPermissionGranted: Boolean = false,
    val isNotificationEnabled: Boolean = false,
    val azureEndpoint: String = "",
    val azureApiKey: String = "",
    val azureDeployment: String = "gpt-4.1-mini",
    val azureSpeechDeployment: String = ""
)
