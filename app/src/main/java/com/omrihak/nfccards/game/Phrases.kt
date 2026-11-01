package com.omrihak.nfccards.game

import com.omrihak.nfccards.content.ContentException
import com.omrihak.nfccards.content.ErrorKind

/**
 * What a game says, in one language. `{word}` is replaced by the card's word.
 * Hebrew phrases avoid forms that depend on the child's gender.
 */
data class Phrases(
    val intro: String,
    val ask: String,
    val correct: String,
    val wrong: String,
    val tryAgain: String,
    val finish: String,
) {
    companion object {
        const val WORD = "{word}"

        private val ENGLISH = Phrases(
            intro = "Let's play!",
            ask = "Where is the $WORD?",
            correct = "Yes! $WORD!",
            wrong = "That's the $WORD. Try again!",
            tryAgain = "Try again!",
            finish = "Great job!",
        )

        private val HEBREW = Phrases(
            intro = "בואו נשחק!",
            ask = "איפה $WORD?",
            correct = "כן! $WORD!",
            wrong = "זה $WORD. עוד פעם!",
            tryAgain = "עוד פעם!",
            finish = "כל הכבוד!",
        )

        private val DEFAULTS = mapOf("en" to ENGLISH, "he" to HEBREW)

        /**
         * The built-in phrases for [lang] with the game card's `say` overrides applied.
         * A language without built-in phrases needs every phrase in `say`.
         */
        fun forLanguage(lang: String, say: Map<String, String>): Phrases {
            val base = DEFAULTS[lang]
            fun pick(key: String, default: String?): String =
                say[key] ?: default
                    ?: throw ContentException(
                        ErrorKind.UNSUPPORTED,
                        "No built-in phrases for \"$lang\": add \"$key\" to \"say\" on the game card",
                    )
            return Phrases(
                intro = pick("intro", base?.intro),
                ask = pick("ask", base?.ask),
                correct = pick("correct", base?.correct),
                wrong = pick("wrong", base?.wrong),
                tryAgain = pick("tryAgain", base?.tryAgain),
                finish = pick("finish", base?.finish),
            )
        }

        fun fill(template: String, word: String): String = template.replace(WORD, word)
    }
}
