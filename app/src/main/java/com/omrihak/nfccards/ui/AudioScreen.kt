package com.omrihak.nfccards.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.omrihak.nfccards.media.PlayerController
import kotlin.math.min

/** Artwork as large as the screen allows (aspect ratio kept). Tap anywhere to pause or resume. */
@Composable
fun AudioScreen(artwork: Bitmap?, player: PlayerController) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { player.toggle() },
    ) {
        if (artwork != null) {
            val image = remember(artwork) { artwork.asImageBitmap() }
            Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            DefaultArtwork()
        }
        PlayPauseGlyph(
            showPause = player.isPlaying,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).size(150.dp),
        )
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
