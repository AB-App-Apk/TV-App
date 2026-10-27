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
 * Captures a touch as exactly one DOWN followed by one UP, including cancellation/disposal.
 * This does not generate local repeat events. The button's normal onClick remains available for D-pad,
 * keyboard, and accessibility activation; consuming touch events keeps that click from duplicating the gesture.
 */
@Composable
fun Modifier.heldRemoteInput(
    key: HeldRemoteKey,
    onDown: (HeldRemoteKey, String) -> Unit,
    onUp: (HeldRemoteKey, String) -> Unit
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
                try {
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    up?.consume()
                } finally {
                    latestUp.value(key, holdId)
                }
            }
        }
    )
}
