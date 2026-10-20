package com.omrihak.nfccards.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

/** A big translucent play or pause symbol. Purely a picture: the whole screen is the tap target. */
@Composable
fun PlayPauseGlyph(showPause: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        drawCircle(Color.Black.copy(alpha = 0.5f))
        if (showPause) {
            val barW = s * 0.12f
            val top = s * 0.3f
            val h = s * 0.4f
            drawRoundRect(Color.White, Offset(s * 0.3f, top), Size(barW, h), androidx.compose.ui.geometry.CornerRadius(barW * 0.3f))
            drawRoundRect(Color.White, Offset(s * 0.58f, top), Size(barW, h), androidx.compose.ui.geometry.CornerRadius(barW * 0.3f))
        } else {
            val triangle = Path().apply {
                moveTo(s * 0.38f, s * 0.27f)
                lineTo(s * 0.38f, s * 0.73f)
                lineTo(s * 0.76f, s * 0.5f)
                close()
            }
            drawPath(triangle, Color.White)
        }
    }
}
