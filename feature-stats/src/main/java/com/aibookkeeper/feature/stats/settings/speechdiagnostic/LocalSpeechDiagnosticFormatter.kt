package com.aibookkeeper.feature.stats.settings.speechdiagnostic

import android.speech.SpeechRecognizer

internal const val LOCAL_SPEECH_DIAGNOSTIC_TAG = "LocalSpeechDiag"

internal fun speechErrorLabel(error: Int): String {
    val label = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "ERROR_CANNOT_CHECK_SUPPORT"
        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS ->
            "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"

        else -> "UNKNOWN_ERROR"
    }
    return "$label ($error)"
}

internal fun formatLanguageList(values: List<String>): String {
    return if (values.isEmpty()) "[]" else values.joinToString(prefix = "[", postfix = "]")
}

internal fun LocalSpeechDiagnosticUiState.asClipboardText(): String {
    val builder = StringBuilder()
    builder.appendLine("本地语音诊断报告")
    builder.appendLine("languageTag=${languageTag.ifBlank { "未设置" }}")
    builder.appendLine("activeRecognizer=${activeRecognizerLabel ?: "无"}")
    builder.appendLine("latestSupport=${latestSupportSummary.ifBlank { "无" }}")
    builder.appendLine("latestIntent=${latestIntentResult.ifBlank { "无" }}")
    builder.appendLine("latestPartial=${latestPartialText.ifBlank { "无" }}")
    builder.appendLine("latestFinal=${latestFinalText.ifBlank { "无" }}")
    builder.appendLine()
    builder.appendLine("[设备信息]")
    report.deviceInfo.forEach { builder.appendLine("${it.label}: ${it.value}") }
    builder.appendLine()
    builder.appendLine("[能力检查]")
    report.capabilityInfo.forEach { builder.appendLine("${it.label}: ${it.value}") }
    builder.appendLine()
    builder.appendLine("[Secure Settings]")
    report.secureSettings.forEach { builder.appendLine("${it.label}: ${it.value}") }
    builder.appendLine()
    builder.appendLine("[Assistant Role]")
    report.assistantRoleInfo.forEach { builder.appendLine("${it.label}: ${it.value}") }
    builder.appendLine()
    builder.appendLine("[RecognizerIntent Activities]")
    if (report.recognizerActivities.isEmpty()) {
        builder.appendLine("无")
    } else {
        report.recognizerActivities.forEach { builder.appendLine(it.asDisplayText()) }
    }
    builder.appendLine()
    builder.appendLine("[Recognition Services]")
    if (report.recognitionServices.isEmpty()) {
        builder.appendLine("无")
    } else {
        report.recognitionServices.forEach { builder.appendLine(it.asDisplayText()) }
    }
    builder.appendLine()
    builder.appendLine("[Timeline Logs]")
    if (logs.isEmpty()) {
        builder.appendLine("无")
    } else {
        logs.asReversed().forEach { entry ->
            builder.appendLine("${entry.timestamp} ${entry.level.name}: ${entry.message}")
        }
    }
    return builder.toString().trimEnd()
}

