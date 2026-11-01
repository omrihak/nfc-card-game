package com.omrihak.nfccards.game

import com.omrihak.nfccards.content.GoogleDriveUrlResolver

/**
 * Identifies a card. The link on a sticker and the link in a game's deck may be written differently
 * (`.../view?usp=sharing` vs `.../view`), so Drive links are compared by file id.
 */
object CardKey {
    fun of(link: String): String {
        val trimmed = link.trim()
        return GoogleDriveUrlResolver.extractFileId(trimmed)?.let { "drive:$it" } ?: trimmed
    }
}
