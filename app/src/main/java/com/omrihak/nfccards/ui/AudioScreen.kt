package com.omrihak.nfccards.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.omrihak.nfccards.media.PlayerController
import kotlin.math.min

/** Artwork as large as the screen allows (aspect ratio kept). Tap anywhere to pause or resume, long-press to stop. */
@Composable
fun AudioScreen(artwork: Bitmap?, player: PlayerController, onStop: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .pointerInput(player) { detectTapGestures(onTap = { player.toggle() }, onLongPress = { onStop() }) },
    ) {
        if (artwork != null) {
            val image = remember(artwork) { artwork.asImageBitmap() }
            Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            DefaultArtwork()
        }
    }
}

/** Shown when a card has no artwork, or it could not be loaded. */
@Composable
private fun DefaultArtwork() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFFFFB86B), Color(0xFFFF6F91))))
        val u = min(size.width, size.height)
        val c = Offset(size.width / 2f, size.height * 0.45f)
        val head = u * 0.1f
        drawCircle(Color.White, head, Offset(c.x - u * 0.08f, c.y + u * 0.15f))
        val stemX = c.x - u * 0.08f + head * 0.85f
        drawRect(Color.White, Offset(stemX, c.y - u * 0.2f), Size(u * 0.03f, u * 0.35f))
        drawRoundRect(Color.White, Offset(stemX, c.y - u * 0.2f), Size(u * 0.14f, u * 0.07f), CornerRadius(u * 0.03f))
    }
}
