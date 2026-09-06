package com.aibookkeeper.feature.input.components

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

internal val LocalSpeechInputEngineFactory = staticCompositionLocalOf<(Context) -> SpeechInputEngine> {
    { context -> AndroidSpeechInputEngine(context) }
}

@Composable
internal fun rememberSpeechInputSession(
    onText: (String) -> Unit,
    onFinished: () -> Unit = {}
): SpeechInputSession {
    val context = LocalContext.current
    val factory = LocalSpeechInputEngineFactory.current
    val scope = rememberCoroutineScope()
    val currentOnText by rememberUpdatedState(onText)
    val currentOnFinished by rememberUpdatedState(onFinished)
    val session = remember(context, factory, scope) {
        SpeechInputSession(scope, { factory(context) }, { currentOnText(it) }, { currentOnFinished() })
    }
    DisposableEffect(session) {
        onDispose { session.cancel() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { session.cancel() }
    return session
}

internal fun holdToTalkRecognitionIntent(packageName: String): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // The user's release ends a hold-to-talk session, not a 1.2-second pause.
        val silenceLengthMillis = SpeechInputSession.MAX_RECORDING_MILLIS.toInt()
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceLengthMillis)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceLengthMillis)
    }

internal class AndroidSpeechInputEngine(
    private val context: Context,
    private val intentFactory: (String) -> Intent = ::holdToTalkRecognitionIntent
) : SpeechInputEngine {
    private var recognizer: SpeechRecognizer? = null

    override fun start(listener: SpeechInputEngine.Listener) {
        val current = SpeechRecognizer.createSpeechRecognizer(context)
        val segments = mutableListOf<String>()
        recognizer = current
        current.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.ready()
            override fun onPartialResults(partialResults: Bundle?) {
                val text = bestResult(partialResults)
                if (text.isNotBlank()) listener.partial((segments + text).joinToString("\n"))
            }
            override fun onSegmentResults(segmentResults: Bundle) {
                val text = bestResult(segmentResults)
                if (text.isNotBlank()) {
                    segments += text
                    listener.partial(segments.joinToString("\n"))
                }
            }
            override fun onEndOfSegmentedSession() = listener.result(segments.joinToString("\n"))
            override fun onResults(results: Bundle?) {
                val text = bestResult(results)
                listener.result(text.ifBlank { segments.joinToString("\n") })
            }
            override fun onEndOfSpeech() = listener.ended()
            override fun onError(error: Int) = listener.error(when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音内容，请重试"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未听到语音，请在麦克风就绪后说话"
                SpeechRecognizer.ERROR_AUDIO -> "录音错误，请检查麦克风后重试"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络不可用，请检查连接后重试"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别器正忙，请稍后重试"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "请授予麦克风权限后重试"
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "系统中文语音识别不可用，请检查系统语音服务"
                else -> "语音识别失败（$error），请重试"
            })
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        current.startListening(intentFactory(context.packageName))
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        recognizer?.cancel()
    }

    override fun close() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun bestResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}
