package com.omrihak.nfccards.content

import org.json.JSONException
import org.json.JSONObject

/**
 * The small JSON file an NFC tag points to.
 *
 * Fields: `type` ("audio" | "video" | "word" | "game"), `media` (required for audio and video), `image`
 * (optional artwork). A card may have `words` (how it is spoken, see [Words]); a word card is made of nothing
 * else: tapped alone it shows its picture and says its words, and it is what games are played with. A game card
 * has the settings described in [GameSpec]. Unknown fields are ignored but kept in [raw], so later features
 * can read new fields without changing the parser.
 *
 * For word and game cards [media] is empty; for a game card [game] holds the settings.
 */
data class ContentMetadata(
    val type: String,
    val media: String,
    val image: String?,
    val raw: JSONObject,
    val game: GameSpec? = null,
) {
    /** Optional display name (written by the card manager); shown in the companion app. */
    val name: String? get() = raw.stringOrNull("name")

    /** How this card is spoken in games; empty if the card has no `words`. */
    val words: Words get() = Words.parse(raw.optJSONObject("words"))

    companion object {
        const val TYPE_AUDIO = "audio"
        const val TYPE_VIDEO = "video"
        const val TYPE_GAME = "game"
        const val TYPE_WORD = "word"
        private val SUPPORTED_TYPES = setOf(TYPE_AUDIO, TYPE_VIDEO, TYPE_GAME, TYPE_WORD)

        fun parse(text: String): ContentMetadata {
            val json = try {
                JSONObject(text.trim().removePrefix("﻿"))
            } catch (e: JSONException) {
                throw ContentException(ErrorKind.MALFORMED_METADATA, "Metadata is not a JSON object", e)
            }

            val type = json.stringOrNull("type")?.lowercase()
                ?: throw ContentException(ErrorKind.MALFORMED_METADATA, "Metadata has no \"type\"")
            if (type !in SUPPORTED_TYPES) {
                throw ContentException(ErrorKind.UNSUPPORTED, "Unsupported content type \"$type\"")
            }

            if (type == TYPE_GAME || type == TYPE_WORD) {
                val image = json.stringOrNull("image")
                if (image != null) requireHttpUrl(image, "image")
                if (type == TYPE_GAME) return ContentMetadata(type, "", image, json, GameSpec.parse(json))
                if (Words.parse(json.optJSONObject("words")).isEmpty) {
                    throw ContentException(ErrorKind.MALFORMED_METADATA, "Word card has no \"words\"")
                }
                return ContentMetadata(type, "", image, json)
            }

            val media = json.stringOrNull("media")
                ?: throw ContentException(ErrorKind.MISSING_MEDIA, "Metadata has no \"media\"")
            requireHttpUrl(media, "media")

            val image = json.stringOrNull("image")
            if (image != null) requireHttpUrl(image, "image")

            return ContentMetadata(type, media, image, json)
        }

        private fun requireHttpUrl(value: String, field: String) {
            val ok = value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)
            if (!ok) throw ContentException(ErrorKind.INVALID_URL, "\"$field\" is not an http(s) URL: $value")
        }
    }
}
