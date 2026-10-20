package com.omrihak.nfccards.content

import org.json.JSONException
import org.json.JSONObject

/**
 * The small JSON file an NFC tag points to.
 *
 * V1 fields: `type` ("audio" | "video"), `media` (required), `image` (optional artwork).
 * Unknown fields are ignored but kept in [raw], so later features (playlists, games, ...) can read
 * new fields without changing the parser.
 */
data class ContentMetadata(
    val type: String,
    val media: String,
    val image: String?,
    val raw: JSONObject,
) {
    companion object {
        const val TYPE_AUDIO = "audio"
        const val TYPE_VIDEO = "video"
        private val SUPPORTED_TYPES = setOf(TYPE_AUDIO, TYPE_VIDEO)

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

        // org.json's optString() turns a JSON null into the string "null", so check explicitly.
        private fun JSONObject.stringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key).trim().ifEmpty { null }
        }
    }
}
