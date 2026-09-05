package com.aibookkeeper.feature.input.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputChange
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun Modifier.holdToTalkGesture(
    isRecording: Boolean,
    isProcessing: Boolean,
    hasSubmitContent: Boolean,
    onHoldStarted: () -> Unit,
    onHoldReleased: () -> Unit,
    onHoldCancelled: () -> Unit,
    onSubmit: () -> Unit
): Modifier {
    val currentOnHoldStarted by rememberUpdatedState(onHoldStarted)
    val currentOnHoldReleased by rememberUpdatedState(onHoldReleased)
    val currentOnHoldCancelled by rememberUpdatedState(onHoldCancelled)
    val currentOnSubmit by rememberUpdatedState(onSubmit)
    val currentIsRecording by rememberUpdatedState(isRecording)
    val currentIsProcessing by rememberUpdatedState(isProcessing)
    val currentHasSubmitContent by rememberUpdatedState(hasSubmitContent)

    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            val recordingAtDown = currentIsRecording
            if (recordingAtDown) currentOnHoldStarted()
            val upBeforeLongPress =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    ReleaseResult(waitForUpOrCancellation())
                }
            if (upBeforeLongPress != null) {
                val release = upBeforeLongPress.change
                if (release != null) {
                    release.consume()
                    when {
                        recordingAtDown || currentIsRecording -> currentOnHoldReleased()
                        currentHasSubmitContent && !currentIsProcessing -> currentOnSubmit()
                    }
                } else if (recordingAtDown) {
                    currentOnHoldCancelled()
                }
            } else if (recordingAtDown || !currentIsProcessing) {
                var released = false
                try {
                    if (!recordingAtDown && !currentIsRecording) currentOnHoldStarted()
                    val release = waitForUpOrCancellation()
                    if (release != null) {
                        release.consume()
                        released = true
                        currentOnHoldReleased()
                    }
                } finally {
                    if (!released) currentOnHoldCancelled()
                }
            }
        }
    }
}

// A cancelled early gesture is different from reaching the long-press timeout.
private data class ReleaseResult(val change: PointerInputChange?)
