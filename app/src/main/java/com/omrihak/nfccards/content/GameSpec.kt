package com.omrihak.nfccards.content

import org.json.JSONException
import org.json.JSONObject

/**
 * The settings of a game card (`"type": "game"`). Which game it is decides what the settings mean;
 * this class only holds what every game shares plus the raw JSON for the rest.
 *
 * ```
 * { "type": "game", "game": "find-card", "lang": "en", "rounds": 5,
 *   "deck": ["<card link>", "..."], "say": { "ask": "Where is the {word}?" } }
 * ```
 */
data class GameSpec(
    val game: String,
    val name: String?,
    /** Language the game speaks: "en", "he", or "he>en" (ask in Hebrew, teach the English word). */
    val lang: GameLanguage,
    /** Cards that play; each is the link of an item card's content.json. */
    val deck: List<String>,
    val rounds: Int?,
    /** Phrase overrides ("intro", "ask", "correct", "wrong", "tryAgain", "finish"); `{word}` is filled in. */
    val say: Map<String, String>,
    val raw: JSONObject,
) {
    companion object {
        fun parse(json: JSONObject): GameSpec {
            val game = json.stringOrNull("game")
                ?: throw ContentException(ErrorKind.MALFORMED_METADATA, "Game card has no \"game\"")
            val lang = GameLanguage.parse(json.stringOrNull("lang") ?: "en")
                ?: throw ContentException(ErrorKind.MALFORMED_METADATA, "Unknown \"lang\": ${json.optString("lang")}")

            val deckJson = json.optJSONArray("deck")
                ?: throw ContentException(ErrorKind.MALFORMED_METADATA, "Game card has no \"deck\"")
            val deck = ArrayList<String>()
            for (i in 0 until deckJson.length()) {
                val link = try {
                    deckJson.getString(i).trim()
                } catch (e: JSONException) {
                    throw ContentException(ErrorKind.MALFORMED_METADATA, "\"deck\" entry $i is not a string", e)
                }
                if (!link.isHttpUrl()) {
                    throw ContentException(ErrorKind.INVALID_URL, "\"deck\" entry $i is not an http(s) URL: $link")
                }
                if (link !in deck) deck += link
            }
            if (deck.isEmpty()) throw ContentException(ErrorKind.MALFORMED_METADATA, "\"deck\" is empty")

            val rounds = if (json.has("rounds") && !json.isNull("rounds")) {
                json.optInt("rounds", 0).also {
                    if (it < 1) throw ContentException(ErrorKind.MALFORMED_METADATA, "\"rounds\" must be 1 or more")
                }
            } else {
                null
            }

            val say = LinkedHashMap<String, String>()
            json.optJSONObject("say")?.let { sayJson ->
                for (key in sayJson.keys()) sayJson.stringOrNull(key)?.let { say[key] = it }
            }
            return GameSpec(game.lowercase(), json.stringOrNull("name"), lang, deck, rounds, say, json)
        }
    }
}

/** Which language a game asks in and which it teaches. They differ only for "he>en". */
data class GameLanguage(val ask: String, val answer: String) {
    companion object {
        fun parse(value: String): GameLanguage? {
            val parts = value.lowercase().split('>').map { it.trim() }
            return when {
                parts.size == 1 && parts[0].isNotEmpty() -> GameLanguage(parts[0], parts[0])
                parts.size == 2 && parts.all { it.isNotEmpty() } -> GameLanguage(parts[0], parts[1])
                else -> null
            }
        }
    }
}
