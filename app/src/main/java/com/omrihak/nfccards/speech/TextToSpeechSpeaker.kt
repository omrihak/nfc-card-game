package com.omrihak.nfccards.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android's text-to-speech. Prefers Google's engine (it has a Hebrew voice on the player phone) and falls back
 * to the phone's default engine. Needs the English and Hebrew voice data installed on the phone
 * (Settings -> General management -> Text-to-speech). Main thread only.
 */
class TextToSpeechSpeaker(private val context: Context) : Speaker {

    private var engine: TextToSpeech? = null

    override suspend fun speak(text: String, lang: String) {
        val tts = engine ?: start().also { engine = it }
        val locale = localeFor(lang)
        when (tts.setLanguage(locale)) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED ->
                throw SpeechUnavailableException("No text-to-speech voice for $locale")
        }
        suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
            val id = UUID.randomUUID().toString()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = fail(utteranceId, -1)

                override fun onError(utteranceId: String?, errorCode: Int) = fail(utteranceId, errorCode)

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    // Stopped by us (the caller was cancelled, so the coroutine is already done) or by the system.
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }

                private fun fail(utteranceId: String?, code: Int) {
                    if (utteranceId == id && cont.isActive) {
                        cont.resumeWithException(SpeechUnavailableException("Text-to-speech error $code"))
                    }
                }
            })
            cont.invokeOnCancellation { tts.stop() }
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) == TextToSpeech.ERROR) {
                cont.resumeWithException(SpeechUnavailableException("Text-to-speech refused to speak"))
            }
        }
    }

    fun release() {
        engine?.shutdown()
        engine = null
    }

    /** Starts the engine; Google's first, the default one if that is not installed or fails to start. */
    private suspend fun start(): TextToSpeech =
        try {
            create(GOOGLE_ENGINE)
        } catch (e: SpeechUnavailableException) {
            Log.w(TAG, "Google text-to-speech unavailable (${e.message}); using the default engine")
            create(null)
        }

    private suspend fun create(enginePackage: String?): TextToSpeech =
        suspendCancellableCoroutine { cont ->
            var created: TextToSpeech? = null
            val onInit = TextToSpeech.OnInitListener { status ->
                val tts = created
                when {
                    !cont.isActive -> tts?.shutdown()
                    status == TextToSpeech.SUCCESS && tts != null -> {
                        tts.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        )
                        tts.setSpeechRate(SPEECH_RATE)
                        cont.resume(tts)
                    }
                    else -> {
                        tts?.shutdown()
                        cont.resumeWithException(SpeechUnavailableException("Text-to-speech failed to start ($status)"))
                    }
                }
            }
            // The listener can run before the constructor returns; it then sees `created == null` and fails,
            // so hand it over through a small post to the main looper instead.
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val deferred = TextToSpeech.OnInitListener { status -> handler.post { onInit.onInit(status) } }
            created = if (enginePackage != null) TextToSpeech(context, deferred, enginePackage) else TextToSpeech(context, deferred)
            cont.invokeOnCancellation { created?.shutdown() }
        }

    private companion object {
        const val TAG = "TextToSpeechSpeaker"
        const val GOOGLE_ENGINE = "com.google.android.tts"
        /** A little slower than normal, for a small child. */
        const val SPEECH_RATE = 0.9f

        fun localeFor(lang: String): Locale = Locale.forLanguageTag(
            when (lang.lowercase()) {
                "he", "iw" -> "he-IL"
                "en" -> "en-US"
                else -> lang
            },
        )
    }
}
