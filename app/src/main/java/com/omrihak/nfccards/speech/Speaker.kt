package com.omrihak.nfccards.speech

/** Says a phrase out loud. Kept apart from Android so games can be tested with a fake. */
interface Speaker {
    /**
     * Speaks [text] in language [lang] ("en", "he") and returns when it has been said.
     * Cancelling the caller stops the speech. Throws if there is no voice for [lang].
     */
    suspend fun speak(text: String, lang: String)
}

class SpeechUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
