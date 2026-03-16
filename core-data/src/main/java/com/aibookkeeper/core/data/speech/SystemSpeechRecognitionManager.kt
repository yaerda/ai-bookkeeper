package com.aibookkeeper.core.data.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class SystemSpeechRecognitionAvailability(
    val defaultRecognizerActivity: String = "",
    val voiceRecognitionService: String = "",
    val recognizerIntentActivityCount: Int = 0,
    val recognitionServiceCount: Int = 0,
    val isRecognitionAvailable: Boolean = false,
    val isOnDeviceRecognitionAvailable: Boolean = false
) {
    val canUseSystemSpeech: Boolean
        get() = recognizerIntentActivityCount > 0 && isRecognitionAvailable
}

@Singleton
class SystemSpeechRecognitionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun getAvailability(): SystemSpeechRecognitionAvailability {
        val packageManager = context.packageManager
        val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val recognitionServiceIntent = Intent(RecognitionService.SERVICE_INTERFACE)

        return SystemSpeechRecognitionAvailability(
            defaultRecognizerActivity = packageManager
                .resolveActivityCompat(recognitionIntent)
                ?.flattenToShortString()
                .orEmpty(),
            voiceRecognitionService = readSecureSetting("voice_recognition_service"),
            recognizerIntentActivityCount = packageManager.queryIntentActivitiesCompat(recognitionIntent).size,
            recognitionServiceCount = packageManager.queryIntentServicesCompat(recognitionServiceIntent).size,
            isRecognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context),
            isOnDeviceRecognitionAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            } else {
                false
            }
        )
    }

    fun buildRecognitionIntent(languageTag: String = Locale.getDefault().toLanguageTag()): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出记账内容")
        }
    }

    fun extractBestResult(data: Intent?): String? {
        return data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun readSecureSetting(key: String): String {
        return try {
            Settings.Secure.getString(context.contentResolver, key).orEmpty()
        } catch (_: SecurityException) {
            ""
        } catch (_: IllegalArgumentException) {
            ""
        }
    }
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

private fun PackageManager.queryIntentActivitiesCompat(intent: Intent): List<android.content.pm.ResolveInfo> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, PackageManager.MATCH_ALL)
    }
}

private fun PackageManager.queryIntentServicesCompat(intent: Intent): List<android.content.pm.ResolveInfo> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
    } else {
        @Suppress("DEPRECATION")
        queryIntentServices(intent, PackageManager.MATCH_ALL)
    }
}
