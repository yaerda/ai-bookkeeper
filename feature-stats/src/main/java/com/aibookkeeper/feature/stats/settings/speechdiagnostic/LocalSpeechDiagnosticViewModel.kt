package com.aibookkeeper.feature.stats.settings.speechdiagnostic

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class DiagnosticKeyValue(
    val label: String,
    val value: String
)

enum class DiagnosticLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

data class SpeechDiagnosticLogEntry(
    val timestamp: String,
    val level: DiagnosticLogLevel,
    val message: String
)

data class SpeechComponentInfo(
    val label: String,
    val packageName: String,
    val className: String,
    val exported: Boolean,
    val componentEnabled: Boolean,
    val appEnabled: Boolean
) {
    val componentId: String
        get() = "$packageName/$className"

    fun toComponentName(): ComponentName = ComponentName(packageName, className)

    fun asDisplayText(): String {
        val readableLabel = label.ifBlank { packageName }
        return buildString {
            append(readableLabel)
            append(" -> ")
            append(componentId)
            append(" | exported=")
            append(exported)
            append(" | componentEnabled=")
            append(componentEnabled)
            append(" | appEnabled=")
            append(appEnabled)
        }
    }
}

data class LocalSpeechEnvironmentReport(
    val capturedAt: String = "",
    val deviceInfo: List<DiagnosticKeyValue> = emptyList(),
    val capabilityInfo: List<DiagnosticKeyValue> = emptyList(),
    val secureSettings: List<DiagnosticKeyValue> = emptyList(),
    val assistantRoleInfo: List<DiagnosticKeyValue> = emptyList(),
    val recognizerActivities: List<SpeechComponentInfo> = emptyList(),
    val recognitionServices: List<SpeechComponentInfo> = emptyList()
)

data class LocalSpeechDiagnosticUiState(
    val languageTag: String = Locale.getDefault().toLanguageTag(),
    val report: LocalSpeechEnvironmentReport = LocalSpeechEnvironmentReport(),
    val isListening: Boolean = false,
    val activeRecognizerLabel: String? = null,
    val latestSupportSummary: String = "",
    val latestIntentResult: String = "",
    val latestPartialText: String = "",
    val latestFinalText: String = "",
    val logs: List<SpeechDiagnosticLogEntry> = emptyList()
)

private const val MAX_LOG_ENTRIES = 200
private const val RMS_LOG_INTERVAL_MS = 750L

private sealed interface RecognizerLaunchTarget {
    val label: String
    val preferOffline: Boolean
}

private data object DefaultRecognizerTarget : RecognizerLaunchTarget {
    override val label: String = "默认 SpeechRecognizer"
    override val preferOffline: Boolean = false
}

private data object OfflinePreferredRecognizerTarget : RecognizerLaunchTarget {
    override val label: String = "默认 SpeechRecognizer（优先离线）"
    override val preferOffline: Boolean = true
}

private data object OnDeviceRecognizerTarget : RecognizerLaunchTarget {
    override val label: String = "On-device SpeechRecognizer"
    override val preferOffline: Boolean = true
}

private data class ExplicitServiceRecognizerTarget(
    val service: SpeechComponentInfo
) : RecognizerLaunchTarget {
    override val label: String = "显式服务 ${service.label.ifBlank { service.packageName }}"
    override val preferOffline: Boolean = false
}

@HiltViewModel
class LocalSpeechDiagnosticViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocalSpeechDiagnosticUiState())
    val uiState: StateFlow<LocalSpeechDiagnosticUiState> = _uiState.asStateFlow()

    private var activeRecognizer: SpeechRecognizer? = null
    private var lastRmsLogElapsedRealtime: Long = 0L

    init {
        refreshEnvironment()
    }

    fun setLanguageTag(value: String) {
        _uiState.update { it.copy(languageTag = value) }
    }

    fun refreshEnvironment() {
        val report = collectEnvironmentReport()
        _uiState.update { it.copy(report = report) }
        appendLog(
            level = DiagnosticLogLevel.INFO,
            message = buildString {
                append("刷新环境完成：")
                append("activity=")
                append(report.recognizerActivities.size)
                append("，service=")
                append(report.recognitionServices.size)
                append("，capturedAt=")
                append(report.capturedAt)
            }
        )
    }

    fun onAudioPermissionResult(granted: Boolean) {
        appendLog(
            level = if (granted) DiagnosticLogLevel.INFO else DiagnosticLogLevel.WARN,
            message = if (granted) {
                "RECORD_AUDIO 已授权，可继续程序化 SpeechRecognizer 测试"
            } else {
                "RECORD_AUDIO 未授权，程序化 SpeechRecognizer 测试将直接失败"
            }
        )
        refreshEnvironment()
    }

    fun prepareRecognizerIntent(preferOffline: Boolean): Intent {
        val label = recognizerIntentLabel(preferOffline)
        val languageTag = normalizedLanguageTag()
        appendLog(
            level = DiagnosticLogLevel.INFO,
            message = "$label：准备启动系统语音面板，language=$languageTag，preferOffline=$preferOffline"
        )
        return buildRecognitionIntent(
            packageName = context.packageName,
            languageTag = languageTag,
            preferOffline = preferOffline,
            prompt = label
        )
    }

    fun onRecognizerIntentUnavailable(preferOffline: Boolean) {
        appendLog(
            level = DiagnosticLogLevel.WARN,
            message = "${recognizerIntentLabel(preferOffline)}：未找到可处理 ACTION_RECOGNIZE_SPEECH 的 Activity"
        )
    }

    fun handleRecognizerIntentResult(resultCode: Int, data: Intent?) {
        val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        val confidenceScores = data?.getFloatArrayExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES)
        val summary = buildString {
            append(resultCodeLabel(resultCode))
            if (results.isNotEmpty()) {
                append("，results=")
                append(results.joinToString())
            }
            if (confidenceScores != null && confidenceScores.isNotEmpty()) {
                append("，confidence=")
                append(confidenceScores.joinToString(prefix = "[", postfix = "]") {
                    String.format(Locale.US, "%.2f", it)
                })
            }
        }
        _uiState.update {
            it.copy(
                latestIntentResult = summary,
                latestFinalText = results.firstOrNull().orEmpty().ifBlank { it.latestFinalText }
            )
        }
        appendLog(DiagnosticLogLevel.INFO, "RecognizerIntent 返回：$summary")
    }

    fun startDefaultRecognizer() {
        startRecognizer(DefaultRecognizerTarget)
    }

    fun startOfflinePreferredRecognizer() {
        startRecognizer(OfflinePreferredRecognizerTarget)
    }

    fun startOnDeviceRecognizer() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            appendLog(DiagnosticLogLevel.WARN, "当前系统 API < 31，不支持 createOnDeviceSpeechRecognizer")
            return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            appendLog(DiagnosticLogLevel.WARN, "系统报告 on-device speech recognizer 不可用")
            refreshEnvironment()
            return
        }
        startRecognizer(OnDeviceRecognizerTarget)
    }

    fun startExplicitServiceRecognizer(service: SpeechComponentInfo) {
        startRecognizer(ExplicitServiceRecognizerTarget(service))
    }

    fun stopListening() {
        val recognizer = activeRecognizer
        if (recognizer == null) {
            appendLog(DiagnosticLogLevel.WARN, "stopListening：当前没有活动中的 recognizer")
            return
        }
        try {
            recognizer.stopListening()
            appendLog(DiagnosticLogLevel.INFO, "stopListening 已调用，等待最终回调")
        } catch (error: IllegalStateException) {
            appendLog(DiagnosticLogLevel.ERROR, "stopListening 失败：${error.message ?: error::class.java.simpleName}")
        } catch (error: SecurityException) {
            appendLog(DiagnosticLogLevel.ERROR, "stopListening 权限异常：${error.message ?: error::class.java.simpleName}")
        }
    }

    fun cancelListening() {
        val recognizer = activeRecognizer
        if (recognizer == null) {
            appendLog(DiagnosticLogLevel.WARN, "cancelListening：当前没有活动中的 recognizer")
            return
        }
        try {
            recognizer.cancel()
            _uiState.update { it.copy(isListening = false, activeRecognizerLabel = null) }
            appendLog(DiagnosticLogLevel.WARN, "cancelListening 已调用")
        } catch (error: IllegalStateException) {
            appendLog(DiagnosticLogLevel.ERROR, "cancelListening 失败：${error.message ?: error::class.java.simpleName}")
        } catch (error: SecurityException) {
            appendLog(DiagnosticLogLevel.ERROR, "cancelListening 权限异常：${error.message ?: error::class.java.simpleName}")
        }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    override fun onCleared() {
        destroyActiveRecognizer(logReason = null)
        super.onCleared()
    }

    private fun startRecognizer(target: RecognizerLaunchTarget) {
        if (!hasRecordAudioPermission()) {
            appendLog(DiagnosticLogLevel.WARN, "${target.label}：缺少 RECORD_AUDIO 权限")
            refreshEnvironment()
            return
        }

        destroyActiveRecognizer(logReason = "启动 ${target.label} 前清理旧实例")

        val languageTag = normalizedLanguageTag()
        val recognizerIntent = buildRecognitionIntent(
            packageName = context.packageName,
            languageTag = languageTag,
            preferOffline = target.preferOffline,
            prompt = target.label
        )
        val recognizer = createRecognizer(target) ?: return

        lastRmsLogElapsedRealtime = 0L
        activeRecognizer = recognizer
        recognizer.setRecognitionListener(LoggingRecognitionListener(target.label))
        _uiState.update {
            it.copy(
                isListening = true,
                activeRecognizerLabel = target.label,
                latestSupportSummary = "",
                latestPartialText = "",
                latestFinalText = ""
            )
        }

        appendLog(
            level = DiagnosticLogLevel.INFO,
            message = "${target.label}：recognizer 已创建，language=$languageTag，preferOffline=${target.preferOffline}"
        )
        checkRecognitionSupport(target.label, recognizer, recognizerIntent)

        try {
            recognizer.startListening(recognizerIntent)
            appendLog(DiagnosticLogLevel.INFO, "${target.label}：startListening 已调用")
        } catch (error: IllegalStateException) {
            appendLog(DiagnosticLogLevel.ERROR, "${target.label}：startListening 失败（状态异常）${error.message ?: error::class.java.simpleName}")
            destroyActiveRecognizer(logReason = "startListening 状态异常")
        } catch (error: SecurityException) {
            appendLog(DiagnosticLogLevel.ERROR, "${target.label}：startListening 权限异常 ${error.message ?: error::class.java.simpleName}")
            destroyActiveRecognizer(logReason = "startListening 权限异常")
        } catch (error: UnsupportedOperationException) {
            appendLog(DiagnosticLogLevel.ERROR, "${target.label}：startListening 不受支持 ${error.message ?: error::class.java.simpleName}")
            destroyActiveRecognizer(logReason = "startListening 不受支持")
        }
    }

    private fun createRecognizer(target: RecognizerLaunchTarget): SpeechRecognizer? {
        return try {
            when (target) {
                DefaultRecognizerTarget,
                OfflinePreferredRecognizerTarget -> SpeechRecognizer.createSpeechRecognizer(context)

                OnDeviceRecognizerTarget -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } else {
                        appendLog(DiagnosticLogLevel.WARN, "${target.label}：需要 Android 12+ (API 31)")
                        null
                    }
                }

                is ExplicitServiceRecognizerTarget -> {
                    SpeechRecognizer.createSpeechRecognizer(context, target.service.toComponentName())
                }
            }
        } catch (error: IllegalArgumentException) {
            appendLog(DiagnosticLogLevel.ERROR, "${target.label}：createSpeechRecognizer 参数异常 ${error.message ?: error::class.java.simpleName}")
            null
        } catch (error: IllegalStateException) {
            appendLog(DiagnosticLogLevel.ERROR, "${target.label}：createSpeechRecognizer 状态异常 ${error.message ?: error::class.java.simpleName}")
            null
        } catch (error: SecurityException) {
            appendLog(DiagnosticLogLevel.ERROR, "${target.label}：createSpeechRecognizer 权限异常 ${error.message ?: error::class.java.simpleName}")
            null
        } catch (error: UnsupportedOperationException) {
            appendLog(DiagnosticLogLevel.ERROR, "${target.label}：设备不支持该 recognizer ${error.message ?: error::class.java.simpleName}")
            null
        }
    }

    private fun checkRecognitionSupport(
        recognizerLabel: String,
        recognizer: SpeechRecognizer,
        recognizerIntent: Intent
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            _uiState.update { it.copy(latestSupportSummary = "API < 33，系统不支持 checkRecognitionSupport") }
            appendLog(DiagnosticLogLevel.DEBUG, "$recognizerLabel：API < 33，跳过 checkRecognitionSupport")
            return
        }

        try {
            recognizer.checkRecognitionSupport(
                recognizerIntent,
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        val summary = buildString {
                            append("installed=")
                            append(formatLanguageList(recognitionSupport.installedOnDeviceLanguages))
                            append("，pending=")
                            append(formatLanguageList(recognitionSupport.pendingOnDeviceLanguages))
                            append("，supported=")
                            append(formatLanguageList(recognitionSupport.supportedOnDeviceLanguages))
                            append("，online=")
                            append(formatLanguageList(recognitionSupport.onlineLanguages))
                        }
                        _uiState.update { it.copy(latestSupportSummary = summary) }
                        appendLog(DiagnosticLogLevel.INFO, "$recognizerLabel：support=$summary")
                    }

                    override fun onError(error: Int) {
                        val summary = speechErrorLabel(error)
                        _uiState.update { it.copy(latestSupportSummary = summary) }
                        appendLog(DiagnosticLogLevel.WARN, "$recognizerLabel：support check error=$summary")
                    }
                }
            )
        } catch (error: IllegalStateException) {
            appendLog(DiagnosticLogLevel.ERROR, "$recognizerLabel：checkRecognitionSupport 状态异常 ${error.message ?: error::class.java.simpleName}")
        } catch (error: SecurityException) {
            appendLog(DiagnosticLogLevel.ERROR, "$recognizerLabel：checkRecognitionSupport 权限异常 ${error.message ?: error::class.java.simpleName}")
        } catch (error: UnsupportedOperationException) {
            appendLog(DiagnosticLogLevel.ERROR, "$recognizerLabel：checkRecognitionSupport 不受支持 ${error.message ?: error::class.java.simpleName}")
        }
    }

    private fun collectEnvironmentReport(): LocalSpeechEnvironmentReport {
        val packageManager = context.packageManager
        val recognizeIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val recognitionServiceIntent = Intent(RecognitionService.SERVICE_INTERFACE)
        val resolvedActivity = packageManager.resolveActivityCompat(recognizeIntent)
        val recognizerActivities = packageManager.queryIntentActivitiesCompat(recognizeIntent)
            .map { it.toSpeechComponentInfo(packageManager, isService = false) }
            .sortedBy { it.packageName }
        val recognitionServices = packageManager.queryIntentServicesCompat(recognitionServiceIntent)
            .map { it.toSpeechComponentInfo(packageManager, isService = true) }
            .sortedBy { it.packageName }

        return LocalSpeechEnvironmentReport(
            capturedAt = timestampNow(),
            deviceInfo = buildList {
                add(DiagnosticKeyValue("manufacturer", Build.MANUFACTURER))
                add(DiagnosticKeyValue("brand", Build.BRAND))
                add(DiagnosticKeyValue("model", Build.MODEL))
                add(DiagnosticKeyValue("device", Build.DEVICE))
                add(DiagnosticKeyValue("display", Build.DISPLAY))
                add(DiagnosticKeyValue("sdkInt", Build.VERSION.SDK_INT.toString()))
                add(DiagnosticKeyValue("release", Build.VERSION.RELEASE.orEmpty()))
                add(DiagnosticKeyValue("locale", Locale.getDefault().toLanguageTag()))
                add(DiagnosticKeyValue("packageName", context.packageName))
            },
            capabilityInfo = buildList {
                add(DiagnosticKeyValue("RECORD_AUDIO 权限", if (hasRecordAudioPermission()) "已授权" else "未授权"))
                add(
                    DiagnosticKeyValue(
                        "RecognizerIntent 默认 Activity",
                        resolvedActivity?.flattenToShortString() ?: "未解析到"
                    )
                )
                add(DiagnosticKeyValue("RecognizerIntent Activity 数量", recognizerActivities.size.toString()))
                add(DiagnosticKeyValue("RecognitionService 数量", recognitionServices.size.toString()))
                add(
                    DiagnosticKeyValue(
                        "SpeechRecognizer.isRecognitionAvailable",
                        SpeechRecognizer.isRecognitionAvailable(context).toString()
                    )
                )
                add(
                    DiagnosticKeyValue(
                        "SpeechRecognizer.isOnDeviceRecognitionAvailable",
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            SpeechRecognizer.isOnDeviceRecognitionAvailable(context).toString()
                        } else {
                            "API < 31"
                        }
                    )
                )
            },
            secureSettings = listOf(
                readSecureSetting("assistant", "assistant"),
                readSecureSetting("voice_interaction_service", "voice_interaction_service"),
                readSecureSetting("voice_recognition_service", "voice_recognition_service")
            ),
            assistantRoleInfo = readAssistantRoleInfo(),
            recognizerActivities = recognizerActivities,
            recognitionServices = recognitionServices
        )
    }

    private fun readAssistantRoleInfo(): List<DiagnosticKeyValue> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return listOf(DiagnosticKeyValue("assistant role", "API < 29"))
        }

        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager == null) {
            return listOf(DiagnosticKeyValue("assistant role", "RoleManager unavailable"))
        }

        return listOf(
            DiagnosticKeyValue(
                "ROLE_ASSISTANT available",
                roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT).toString()
            ),
            DiagnosticKeyValue(
                "ROLE_ASSISTANT heldByThisApp",
                roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT).toString()
            )
        )
    }

    private fun readSecureSetting(label: String, key: String): DiagnosticKeyValue {
        return try {
            val value = Settings.Secure.getString(context.contentResolver, key)
            DiagnosticKeyValue(label, value.takeUnless { it.isNullOrBlank() } ?: "<empty>")
        } catch (error: SecurityException) {
            DiagnosticKeyValue(label, "SecurityException: ${error.message ?: error::class.java.simpleName}")
        } catch (error: IllegalArgumentException) {
            DiagnosticKeyValue(label, "IllegalArgumentException: ${error.message ?: error::class.java.simpleName}")
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun normalizedLanguageTag(): String {
        val current = _uiState.value.languageTag.trim()
        return current.ifBlank { Locale.getDefault().toLanguageTag() }
    }

    private fun destroyActiveRecognizer(logReason: String?) {
        val recognizer = activeRecognizer ?: return
        if (!logReason.isNullOrBlank()) {
            appendLog(DiagnosticLogLevel.DEBUG, "销毁 recognizer：$logReason")
        }
        recognizer.destroy()
        activeRecognizer = null
        _uiState.update { it.copy(isListening = false, activeRecognizerLabel = null) }
    }

    private fun appendLog(level: DiagnosticLogLevel, message: String) {
        when (level) {
            DiagnosticLogLevel.DEBUG -> Log.d(LOCAL_SPEECH_DIAGNOSTIC_TAG, message)
            DiagnosticLogLevel.INFO -> Log.i(LOCAL_SPEECH_DIAGNOSTIC_TAG, message)
            DiagnosticLogLevel.WARN -> Log.w(LOCAL_SPEECH_DIAGNOSTIC_TAG, message)
            DiagnosticLogLevel.ERROR -> Log.e(LOCAL_SPEECH_DIAGNOSTIC_TAG, message)
        }

        val entry = SpeechDiagnosticLogEntry(
            timestamp = timestampNow(),
            level = level,
            message = message
        )
        _uiState.update { state ->
            state.copy(logs = (listOf(entry) + state.logs).take(MAX_LOG_ENTRIES))
        }
    }

    private inner class LoggingRecognitionListener(
        private val recognizerLabel: String
    ) : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            appendLog(
                DiagnosticLogLevel.INFO,
                "$recognizerLabel：onReadyForSpeech keys=${params?.keySet()?.sorted()?.joinToString().orEmpty()}"
            )
        }

        override fun onBeginningOfSpeech() {
            appendLog(DiagnosticLogLevel.INFO, "$recognizerLabel：onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRmsLogElapsedRealtime >= RMS_LOG_INTERVAL_MS) {
                lastRmsLogElapsedRealtime = now
                appendLog(
                    DiagnosticLogLevel.DEBUG,
                    "$recognizerLabel：onRmsChanged=${String.format(Locale.US, "%.1f", rmsdB)}dB"
                )
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            appendLog(
                DiagnosticLogLevel.DEBUG,
                "$recognizerLabel：onBufferReceived size=${buffer?.size ?: 0}"
            )
        }

        override fun onEndOfSpeech() {
            appendLog(DiagnosticLogLevel.INFO, "$recognizerLabel：onEndOfSpeech")
        }

        override fun onError(error: Int) {
            val label = speechErrorLabel(error)
            _uiState.update { it.copy(isListening = false, activeRecognizerLabel = null) }
            appendLog(DiagnosticLogLevel.ERROR, "$recognizerLabel：onError=$label")
            destroyActiveRecognizer(logReason = "$recognizerLabel onError 后释放 recognizer")
        }

        override fun onResults(results: Bundle) {
            val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val confidenceScores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            val summary = summarizeRecognitionBundle(texts, confidenceScores, results)
            _uiState.update {
                it.copy(
                    isListening = false,
                    activeRecognizerLabel = null,
                    latestFinalText = texts.firstOrNull().orEmpty(),
                    latestPartialText = ""
                )
            }
            appendLog(DiagnosticLogLevel.INFO, "$recognizerLabel：onResults $summary")
            destroyActiveRecognizer(logReason = "$recognizerLabel onResults 后释放 recognizer")
        }

        override fun onPartialResults(partialResults: Bundle) {
            val texts = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val confidenceScores = partialResults.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            val summary = summarizeRecognitionBundle(texts, confidenceScores, partialResults)
            _uiState.update { it.copy(latestPartialText = texts.firstOrNull().orEmpty()) }
            appendLog(DiagnosticLogLevel.INFO, "$recognizerLabel：onPartialResults $summary")
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            appendLog(
                DiagnosticLogLevel.DEBUG,
                "$recognizerLabel：onEvent type=$eventType keys=${params?.keySet()?.sorted()?.joinToString().orEmpty()}"
            )
        }
    }
}

private fun recognizerIntentLabel(preferOffline: Boolean): String {
    return if (preferOffline) "RecognizerIntent（优先离线）" else "RecognizerIntent"
}

private fun buildRecognitionIntent(
    packageName: String,
    languageTag: String,
    preferOffline: Boolean,
    prompt: String
): Intent {
    return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
    }
}

private fun resultCodeLabel(resultCode: Int): String {
    return when (resultCode) {
        Activity.RESULT_OK -> "RESULT_OK"
        Activity.RESULT_CANCELED -> "RESULT_CANCELED"
        else -> "RESULT_$resultCode"
    }
}

private fun summarizeRecognitionBundle(
    texts: List<String>,
    confidenceScores: FloatArray?,
    bundle: Bundle
): String {
    val builder = StringBuilder()
    builder.append("texts=")
    builder.append(if (texts.isEmpty()) "[]" else texts.joinToString(prefix = "[", postfix = "]"))
    if (confidenceScores != null && confidenceScores.isNotEmpty()) {
        builder.append("，confidence=")
        builder.append(
            confidenceScores.joinToString(prefix = "[", postfix = "]") {
                String.format(Locale.US, "%.2f", it)
            }
        )
    }
    builder.append("，keys=")
    builder.append(bundle.keySet().sorted().joinToString(prefix = "[", postfix = "]"))
    return builder.toString()
}

private fun PackageManager.resolveActivityCompat(intent: Intent): ComponentName? {
    val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
    } else {
        @Suppress("DEPRECATION")
        resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }
    val activityInfo = resolveInfo?.activityInfo ?: return null
    return ComponentName(activityInfo.packageName, activityInfo.name)
}

private fun PackageManager.queryIntentActivitiesCompat(intent: Intent): List<ResolveInfo> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, PackageManager.MATCH_ALL)
    }
}

private fun PackageManager.queryIntentServicesCompat(intent: Intent): List<ResolveInfo> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
    } else {
        @Suppress("DEPRECATION")
        queryIntentServices(intent, PackageManager.MATCH_ALL)
    }
}

private fun ResolveInfo.toSpeechComponentInfo(
    packageManager: PackageManager,
    isService: Boolean
): SpeechComponentInfo {
    val componentInfo = if (isService) serviceInfo else activityInfo
    val componentLabel = loadLabel(packageManager)?.toString().orEmpty()
    return SpeechComponentInfo(
        label = componentLabel,
        packageName = componentInfo.packageName,
        className = componentInfo.name,
        exported = componentInfo.exported,
        componentEnabled = componentInfo.enabled,
        appEnabled = componentInfo.applicationInfo.enabled
    )
}

private fun timestampNow(): String {
    return SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
}

