package com.omrihak.nfccards.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Small round live view of the front camera: shows it is on and where to hold a QR code. */
@Composable
fun CameraBubble(preview: PreviewView, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { preview },
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape),
    )
}
