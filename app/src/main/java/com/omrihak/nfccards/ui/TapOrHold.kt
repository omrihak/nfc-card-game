package com.omrihak.nfccards.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** How long a press must be held to stop. Long, so a child resting a hand on the screen doesn't stop it by accident. */
private const val HOLD_TO_STOP_MILLIS = 4_000L

/** Tap: [onTap]. Hold for [HOLD_TO_STOP_MILLIS]: [onHold] (fires while still held, like a long-press). */
fun Modifier.tapOrHold(key: Any?, onTap: () -> Unit, onHold: () -> Unit): Modifier = pointerInput(key) {
    awaitEachGesture {
        awaitFirstDown()
        var ended = false
        val up = withTimeoutOrNull(HOLD_TO_STOP_MILLIS) { waitForUpOrCancellation().also { ended = true } }
        when {
            up != null -> { up.consume(); onTap() }
            !ended -> onHold()
            // else: cancelled (e.g. consumed elsewhere), do nothing.
        }
    }
}
