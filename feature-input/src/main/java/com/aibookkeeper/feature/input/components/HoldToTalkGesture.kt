package com.aibookkeeper.feature.input.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun Modifier.holdToTalkGesture(
    isRecording: Boolean,
    isProcessing: Boolean,
    hasSubmitContent: Boolean,
    onVoiceToggle: () -> Unit,
    onSubmit: () -> Unit
): Modifier {
    val scope = rememberCoroutineScope()
    var stopRecordingJob by remember { mutableStateOf<Job?>(null) }
    val currentOnVoiceToggle by rememberUpdatedState(onVoiceToggle)
    val currentOnSubmit by rememberUpdatedState(onSubmit)
    val currentIsRecording by rememberUpdatedState(isRecording)
    val currentIsProcessing by rememberUpdatedState(isProcessing)
    val currentHasSubmitContent by rememberUpdatedState(hasSubmitContent)

    fun scheduleStop() {
        stopRecordingJob?.cancel()
        stopRecordingJob = scope.launch {
            delay(RELEASE_GRACE_MILLIS)
            if (currentIsRecording) currentOnVoiceToggle()
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopRecordingJob?.cancel() }
    }

    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            stopRecordingJob?.cancel()
            stopRecordingJob = null

            val upBeforeLongPress =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    waitForUpOrCancellation()
                }
            if (upBeforeLongPress != null) {
                when {
                    currentIsRecording -> scheduleStop()
                    currentHasSubmitContent && !currentIsProcessing -> currentOnSubmit()
                }
            } else {
                if (!currentIsProcessing && !currentIsRecording) {
                    currentOnVoiceToggle()
                }
                waitForUpOrCancellation()
                scheduleStop()
            }
        }
    }
}

private const val RELEASE_GRACE_MILLIS = 1_000L
