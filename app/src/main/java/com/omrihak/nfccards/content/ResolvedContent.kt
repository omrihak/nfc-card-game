package com.omrihak.nfccards.content

import android.graphics.Bitmap
import com.omrihak.nfccards.game.GameCard
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

    /**
     * A game card with its deck loaded: [cards] are keyed by [com.omrihak.nfccards.game.CardKey], [hints] holds
     * their pictures (same keys) and [clips] the recordings some words use instead of speech (by URL).
     */
    data class Game(
        val spec: GameSpec,
        val cards: List<GameCard>,
        val hints: Map<String, Bitmap>,
        val clips: Map<String, File>,
        override val title: String? = null,
    ) : ResolvedContent {
        override val mediaUrl: String get() = ""
    }
}
