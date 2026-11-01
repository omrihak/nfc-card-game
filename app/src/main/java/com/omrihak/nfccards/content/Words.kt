package com.omrihak.nfccards.content

import org.json.JSONObject

/**
 * How one card is spoken in one language, from a card's optional `words` field:
 * `"words": { "en": { "text": "dog" }, "he": { "text": "כלב", "ask": "הכלב" } }`.
 *
 * [text] is the word on its own (teaching it, "Yes, dog!"). [ask] is the form used inside a question
 * ("Where is the ...?"); it defaults to [text] and exists for languages where the two differ, like
 * Hebrew's attached "ה". [audio] optionally replaces the synthesized voice with a recording.
 */
data class WordForm(val text: String, val ask: String? = null, val audio: String? = null) {
    val forQuestion: String get() = ask ?: text
}

/** All languages a card can be spoken in, keyed by language code ("en", "he"). */
class Words(private val byLang: Map<String, WordForm>) {
    operator fun get(lang: String): WordForm? = byLang[lang]
    val languages: Set<String> get() = byLang.keys
    val isEmpty: Boolean get() = byLang.isEmpty()

    companion object {
        val NONE = Words(emptyMap())

        /** Lenient: a malformed language entry is skipped, since `words` only matters inside games. */
        fun parse(json: JSONObject?): Words {
            if (json == null) return NONE
            val result = LinkedHashMap<String, WordForm>()
            for (lang in json.keys()) {
                val entry = json.optJSONObject(lang) ?: continue
                val text = entry.stringOrNull("text") ?: continue
                val audio = entry.stringOrNull("audio")?.takeIf { it.isHttpUrl() }
                result[lang.lowercase()] = WordForm(text, entry.stringOrNull("ask"), audio)
            }
            return Words(result)
        }
    }
}

internal fun String.isHttpUrl(): Boolean =
    startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

// org.json's optString() turns a JSON null into the string "null", so check explicitly.
internal fun JSONObject.stringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().ifEmpty { null }
}
