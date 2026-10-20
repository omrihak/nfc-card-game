package com.omrihak.nfccards.ui

import android.graphics.Color as AndroidColor
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.omrihak.nfccards.media.PlayerController

/** Full-screen video, no controls. Tap anywhere to pause or resume. */
@OptIn(UnstableApi::class)
@Composable
fun VideoScreen(player: PlayerController) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { player.toggle() },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    // Taps are handled by the Compose Box above, not by the view.
                    isClickable = false
                    isFocusable = false
                }
            },
            update = { it.player = player.player },
            onRelease = { it.player = null },
        )
    }
}
