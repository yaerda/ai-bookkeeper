package com.aibookkeeper.feature.input.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class SpeechPhase { IDLE, STARTING, LISTENING, PROCESSING }

internal data class SpeechInputState(
    val phase: SpeechPhase = SpeechPhase.IDLE,
    val partialText: String = "",
    val error: String? = null
) {
    val isRecording: Boolean
        get() = phase == SpeechPhase.STARTING || phase == SpeechPhase.LISTENING
    val isProcessing: Boolean
        get() = phase == SpeechPhase.PROCESSING
}

internal interface SpeechInputEngine {
    interface Listener {
        fun ready()
        fun partial(text: String)
        fun ended()
        fun result(text: String)
        fun error(message: String)
    }

    fun start(listener: Listener)
    fun stop()
    fun cancel()
    fun close()
}

internal class SpeechInputSession(
    private val scope: CoroutineScope,
    private val engineFactory: () -> SpeechInputEngine,
    private val onText: (String) -> Unit,
    private val onFinished: () -> Unit
) {
    private val mutableState = MutableStateFlow(SpeechInputState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var engine: SpeechInputEngine? = null
    private var timeout: Job? = null
    private var releaseJob: Job? = null
    private var releaseRequested = false

    fun start() {
        if (state.value.isRecording) {
            releaseJob?.cancel()
            releaseJob = null
            releaseRequested = false
            return
        }
        if (state.value.phase != SpeechPhase.IDLE) return
        val attempt = ++generation
        releaseRequested = false
        mutableState.value = SpeechInputState(SpeechPhase.STARTING)
        timeout = scope.launch {
            delay(READY_TIMEOUT_MILLIS)
            if (attempt == generation && state.value.phase == SpeechPhase.STARTING) {
                finish(error = "麦克风未能就绪，请重试")
            }
        }
        try {
            engine = engineFactory()
            engine?.start(object : SpeechInputEngine.Listener {
                override fun ready() {
                    if (attempt != generation || state.value.phase != SpeechPhase.STARTING) return
                    timeout?.cancel()
                    mutableState.value = state.value.copy(phase = SpeechPhase.LISTENING)
                    timeout = scope.launch {
                        delay(MAX_RECORDING_MILLIS)
                        if (attempt == generation) stop()
                    }
                    if (releaseRequested) release()
                }

                override fun partial(text: String) {
                    if (attempt == generation && text.isNotBlank()) {
                        mutableState.value = state.value.copy(partialText = text.trim())
                    }
                }

                override fun ended() {
                    if (attempt == generation) processing()
                }

                override fun result(text: String) {
                    if (attempt != generation) return
                    val partial = state.value.partialText
                    when {
                        text.isNotBlank() -> finish(text = text.trim())
                        partial.isNotBlank() -> finish(
                            text = partial,
                            error = "系统未返回最终结果，已保留识别文字，请确认后记账"
                        )
                        else -> finish(error = "未识别到语音内容，请重试")
                    }
                }

                override fun error(message: String) {
                    if (attempt == generation) finish(error = message)
                }
            })
        } catch (_: SecurityException) {
            finish(error = "请授予麦克风权限后重试")
        } catch (_: IllegalStateException) {
            finish(error = "语音识别暂时不可用，请重试")
        }
    }

    fun release() {
        releaseRequested = true
        if (state.value.phase != SpeechPhase.LISTENING || releaseJob != null) return
        val attempt = generation
        releaseJob = scope.launch {
            // A cold recognizer must become ready before its release grace starts.
            delay(RELEASE_GRACE_MILLIS)
            if (attempt == generation) stop()
        }
    }

    private fun stop() {
        if (state.value.phase != SpeechPhase.LISTENING) return
        processing()
        try {
            engine?.stop()
        } catch (_: IllegalStateException) {
            finish(error = "语音识别暂时不可用，请重试")
        } catch (_: SecurityException) {
            finish(error = "麦克风权限已失效，请重试")
        }
    }

    private fun processing() {
        if (state.value.phase == SpeechPhase.IDLE || state.value.isProcessing) return
        timeout?.cancel()
        releaseJob?.cancel()
        releaseJob = null
        mutableState.value = state.value.copy(phase = SpeechPhase.PROCESSING)
        val attempt = generation
        timeout = scope.launch {
            delay(RESULT_TIMEOUT_MILLIS)
            if (attempt == generation) finish(error = "语音识别超时，请检查网络后重试")
        }
    }

    fun cancel() {
        generation++
        timeout?.cancel()
        releaseJob?.cancel()
        timeout = null
        releaseJob = null
        releaseRequested = false
        engine?.cancel()
        engine?.close()
        engine = null
        mutableState.value = SpeechInputState()
    }

    private fun finish(text: String? = null, error: String? = null) {
        generation++
        timeout?.cancel()
        releaseJob?.cancel()
        timeout = null
        releaseJob = null
        releaseRequested = false
        engine?.close()
        engine = null
        mutableState.value = SpeechInputState(error = error)
        if (text != null) onText(text)
        onFinished()
    }

    companion object {
        const val RELEASE_GRACE_MILLIS = 1_000L
        const val READY_TIMEOUT_MILLIS = 15_000L
        const val RESULT_TIMEOUT_MILLIS = 15_000L
        const val MAX_RECORDING_MILLIS = 60_000L
    }
}
