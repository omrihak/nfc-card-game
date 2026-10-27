package com.omrihak.nfccards.content

import android.graphics.Bitmap
import java.io.File

/** Content that is fully on local disk and ready to hand to the player. */
sealed interface ResolvedContent {
    val mediaUrl: String
    /** The card's name, if its content.json has one (the card manager writes it). */
    val title: String?

    data class Audio(
        val file: File,
        val artwork: Bitmap?,
        override val mediaUrl: String,
        override val title: String? = null,
    ) : ResolvedContent

    data class Video(val file: File, override val mediaUrl: String, override val title: String? = null) : ResolvedContent
}
