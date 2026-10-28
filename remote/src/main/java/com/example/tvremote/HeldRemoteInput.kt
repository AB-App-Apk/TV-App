package com.example.tvremote

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.example.tvlink.HeldRemoteKey
import java.util.UUID

/**
 * Captures a touch as one DOWN followed by either a normal UP or a cancellation release.
 * This does not generate local repeat events. The button's normal onClick remains available for D-pad,
 * keyboard, and accessibility activation; consuming touch events keeps that click from duplicating the gesture.
 */
@Composable
fun Modifier.heldRemoteInput(
    key: HeldRemoteKey,
    onDown: (HeldRemoteKey, String) -> Unit,
    onUp: (HeldRemoteKey, String, Boolean) -> Unit
): Modifier {
    val latestDown = rememberUpdatedState(onDown)
    val latestUp = rememberUpdatedState(onUp)

    return then(
        Modifier.pointerInput(key) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial
                )
                down.consume()
                val holdId = UUID.randomUUID().toString()
                latestDown.value(key, holdId)
                var cancelled = true
                try {
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    if (up != null) {
                        up.consume()
                        cancelled = false
                    }
                } finally {
                    // Distinguish a real finger-up from cancellation/disposal. The TV must release either way,
                    // but only a real UP may be interpreted as a short Back/OK tap.
                    latestUp.value(key, holdId, cancelled)
                }
            }
        }
    )
}
