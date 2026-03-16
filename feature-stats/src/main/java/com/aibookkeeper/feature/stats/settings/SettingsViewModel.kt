package com.aibookkeeper.feature.stats.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import com.aibookkeeper.core.common.permission.NotificationPermissionHelper
import com.aibookkeeper.core.data.repository.PaymentPagePatternRepository
import com.aibookkeeper.core.data.security.SecureConfigStore
import com.aibookkeeper.core.data.speech.SystemSpeechRecognitionManager
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
    private val secureConfigStore: SecureConfigStore,
    paymentPagePatternRepository: PaymentPagePatternRepository,
    private val systemSpeechRecognitionManager: SystemSpeechRecognitionManager
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
        val speechAvailability = systemSpeechRecognitionManager.getAvailability()
        val isAccessibilityServiceActive = isAccessibilityServiceActive()
        _uiState.update {
            it.copy(
                isPermissionGranted = NotificationPermissionHelper.isPermissionGranted(context),
                isNotificationEnabled = NotificationPermissionHelper.isNotificationEnabled(context),
                isAccessibilityMonitoringEnabled =
                    secureConfigStore.isAccessibilityMonitoringEnabled(),
                isScreenshotCaptureEnabled = secureConfigStore.isScreenshotCaptureEnabled(),
                isAccessibilityServiceActive = isAccessibilityServiceActive,
                azureEndpoint = secureConfigStore.getEndpoint(),
                azureApiKey = secureConfigStore.getApiKey(),
                azureDeployment = secureConfigStore.getDeployment().ifBlank { "gpt-4.1-mini" },
                azureSpeechDeployment = secureConfigStore.getSpeechDeployment(),
                azureTextPrompt = secureConfigStore.getTextPrompt(),
                preferLocalSpeech = secureConfigStore.isLocalSpeechPreferred(),
                isSystemSpeechAvailable = speechAvailability.canUseSystemSpeech,
                isOnDeviceSpeechAvailable = speechAvailability.isOnDeviceRecognitionAvailable,
                systemSpeechProvider = speechAvailability.voiceRecognitionService
                    .ifBlank { speechAvailability.defaultRecognizerActivity },
                systemSpeechSummary = when {
                    speechAvailability.canUseSystemSpeech &&
                        speechAvailability.isOnDeviceRecognitionAvailable -> {
                        "当前系统语音可用，且系统报告 on-device recognizer 可用"
                    }
                    speechAvailability.canUseSystemSpeech -> {
                        "当前系统语音可用，但 on-device recognizer 仍不可用"
                    }
                    else -> {
                        "当前未检测到公开系统语音入口，将回退到 Azure 云端"
                    }
                }
            )
        }
    }

    fun refreshAccessibilityStatus() {
        _uiState.update { it.copy(isAccessibilityServiceActive = isAccessibilityServiceActive()) }
    }

    /** Toggle the persistent notification preference. */
    fun setNotificationEnabled(enabled: Boolean) {
        NotificationPermissionHelper.setNotificationEnabled(context, enabled)
        _uiState.update { it.copy(isNotificationEnabled = enabled) }
    }

    fun setAccessibilityMonitoringEnabled(enabled: Boolean) {
        secureConfigStore.setAccessibilityMonitoringEnabled(enabled)
        _uiState.update { it.copy(isAccessibilityMonitoringEnabled = enabled) }
        refreshAccessibilityStatus()
    }

    fun setScreenshotCaptureEnabled(enabled: Boolean) {
        secureConfigStore.setScreenshotCaptureEnabled(enabled)
        _uiState.update { it.copy(isScreenshotCaptureEnabled = enabled) }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
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

    fun setAzureTextPrompt(value: String) {
        secureConfigStore.setTextPrompt(value)
        _uiState.update { it.copy(azureTextPrompt = value) }
    }

    fun setPreferLocalSpeech(value: Boolean) {
        secureConfigStore.setLocalSpeechPreferred(value)
        _uiState.update { it.copy(preferLocalSpeech = value) }
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

    private fun isAccessibilityServiceActive(): Boolean = runCatching {
        if (Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
            return@runCatching false
        }

        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':').any { service ->
            service.startsWith("${context.packageName}/") ||
                service.contains("${context.packageName}/")
        }
    }.getOrDefault(false)
}

data class SettingsUiState(
    val isPermissionGranted: Boolean = false,
    val isNotificationEnabled: Boolean = false,
    val isAccessibilityMonitoringEnabled: Boolean = false,
    val isScreenshotCaptureEnabled: Boolean = true,
    val isAccessibilityServiceActive: Boolean = false,
    val azureEndpoint: String = "",
    val azureApiKey: String = "",
    val azureDeployment: String = "gpt-4.1-mini",
    val azureSpeechDeployment: String = "",
    val azureTextPrompt: String = "",
    val preferLocalSpeech: Boolean = true,
    val isSystemSpeechAvailable: Boolean = false,
    val isOnDeviceSpeechAvailable: Boolean = false,
    val systemSpeechSummary: String = "",
    val systemSpeechProvider: String = ""
)
