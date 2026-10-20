package com.omrihak.nfccards.content

import android.graphics.Bitmap
import java.io.File

/** Content that is fully on local disk and ready to hand to the player. */
sealed interface ResolvedContent {
    val mediaUrl: String

    data class Audio(val file: File, val artwork: Bitmap?, override val mediaUrl: String) : ResolvedContent
    data class Video(val file: File, override val mediaUrl: String) : ResolvedContent
}
