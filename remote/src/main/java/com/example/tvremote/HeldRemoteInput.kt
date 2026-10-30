package com.example.tvremote

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        // A sibling/parent click handler can consume the UP during the Initial pass. That is not
                        // a cancelled touch: Back/OK deliberately dispatch their tap after release, so dropping
                        // a consumed UP here makes those buttons appear dead over Wi-Fi.
                        if (event.changes.any { it.id != down.id && it.pressed }) break
                        val position = change.position
                        if (
                            position.x < 0f || position.y < 0f ||
                            position.x >= size.width.toFloat() || position.y >= size.height.toFloat()
                        ) break

                        if (change.previousPressed && !change.pressed) {
                            change.consume()
                            cancelled = false
                            break
                        }
                        if (!change.pressed) break
                    }
                } finally {
                    // A physical UP is accepted even if another handler consumed it. Leaving the button bounds,
                    // a second pointer, or coroutine disposal still cancels the gesture.
                    latestUp.value(key, holdId, cancelled)
                }
            }
        }
    )
}
