package com.omrihak.nfccards.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** How long a press must be held to stop. Long, so a child resting a hand on the screen doesn't stop it by accident. */
private const val HOLD_TO_STOP_MILLIS = 4_000L

/**
 * Double tap: [onDoubleTap]. Hold for [HOLD_TO_STOP_MILLIS]: [onHold] (fires while still held, like a long-press).
 * A single tap does nothing, so a stray touch doesn't pause.
 */
fun Modifier.doubleTapOrHold(key: Any?, onDoubleTap: () -> Unit, onHold: () -> Unit): Modifier = pointerInput(key) {
    var lastTapUpMillis: Long? = null
    awaitEachGesture {
        val down = awaitFirstDown()
        var ended = false
        val up = withTimeoutOrNull(HOLD_TO_STOP_MILLIS) { waitForUpOrCancellation().also { ended = true } }
        when {
            up != null -> {
                up.consume()
                val previous = lastTapUpMillis
                if (previous != null && down.uptimeMillis - previous <= viewConfiguration.doubleTapTimeoutMillis) {
                    lastTapUpMillis = null
                    onDoubleTap()
                } else {
                    lastTapUpMillis = up.uptimeMillis
                }
            }
            !ended -> { lastTapUpMillis = null; onHold() }
            // Cancelled (e.g. consumed elsewhere).
            else -> lastTapUpMillis = null
        }
    }
}
