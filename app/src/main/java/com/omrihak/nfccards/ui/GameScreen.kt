package com.omrihak.nfccards.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * While a game runs the phone talks and the child works with the cards, so the screen stays calm: the idle scene
 * with the stars she has earned, and, after two wrong tries, a [hint] picture of the card to find.
 * Hold for 4 seconds to end the game, like for music and videos.
 */
@Composable
fun GameScreen(hint: Bitmap?, stars: Int, onStop: () -> Unit) {
    Box(Modifier.fillMaxSize().doubleTapOrHold(Unit, onDoubleTap = {}, onHold = onStop)) {
        IdleScreen(busy = false, problems = 0, stars = stars)
        if (hint != null) {
            val image = remember(hint) { hint.asImageBitmap() }
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(32.dp),
            )
        }
    }
}
