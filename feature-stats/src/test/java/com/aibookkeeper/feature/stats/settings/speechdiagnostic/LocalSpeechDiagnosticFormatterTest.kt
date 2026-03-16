package com.aibookkeeper.feature.stats.settings.speechdiagnostic

import android.speech.SpeechRecognizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalSpeechDiagnosticFormatterTest {

    @Test
    fun should_mapKnownSpeechErrorCodes() {
        assertEquals(
            "ERROR_LANGUAGE_UNAVAILABLE (${SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE})",
            speechErrorLabel(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        )
        assertEquals(
            "ERROR_RECOGNIZER_BUSY (${SpeechRecognizer.ERROR_RECOGNIZER_BUSY})",
            speechErrorLabel(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        )
    }

    @Test
    fun should_buildClipboardTextWithReportSections() {
        val state = LocalSpeechDiagnosticUiState(
            languageTag = "zh-CN",
            report = LocalSpeechEnvironmentReport(
                deviceInfo = listOf(DiagnosticKeyValue("model", "OnePlus")),
                capabilityInfo = listOf(DiagnosticKeyValue("SpeechRecognizer.isRecognitionAvailable", "false")),
                secureSettings = listOf(DiagnosticKeyValue("assistant", "<empty>")),
                assistantRoleInfo = listOf(DiagnosticKeyValue("ROLE_ASSISTANT holders", "[]")),
                recognizerActivities = listOf(
                    SpeechComponentInfo(
                        label = "Google",
                        packageName = "pkg",
                        className = "pkg.Service",
                        exported = true,
                        componentEnabled = true,
                        appEnabled = true
                    )
                )
            ),
            logs = listOf(
                SpeechDiagnosticLogEntry(
                    timestamp = "12:00:00.000",
                    level = DiagnosticLogLevel.INFO,
                    message = "hello"
                )
            )
        )

        val clipboardText = state.asClipboardText()

        assertTrue(clipboardText.contains("本地语音诊断报告"))
        assertTrue(clipboardText.contains("[设备信息]"))
        assertTrue(clipboardText.contains("[Timeline Logs]"))
        assertTrue(clipboardText.contains("pkg/pkg.Service"))
    }
}
