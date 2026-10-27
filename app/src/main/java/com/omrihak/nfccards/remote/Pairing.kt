package com.omrihak.nfccards.remote

import android.content.Context
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * The parent phones allowed to use the remote control. A companion app pairs once by showing the
 * player its secret token as a QR code (or on a sticker): `tapandplay://pair?token=<token>`.
 * Afterwards it sends the token with every request. Only someone holding the player can pair.
 */
class Pairing(context: Context) {

    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    @Volatile var tokens: List<String> = PairingTokens.decode(prefs.getString(KEY_TOKENS, null))
        private set

    /** Remembers the token of a pairing card. Returns false if [uri] isn't one. Main thread. */
    fun onCard(uri: String): Boolean {
        val token = PairingTokens.tokenFrom(uri) ?: return false
        tokens = PairingTokens.add(tokens, token)
        prefs.edit().putString(KEY_TOKENS, PairingTokens.encode(tokens)).apply()
        return true
    }

    private companion object {
        const val KEY_TOKENS = "tokens"
    }
}

/** The pure logic of [Pairing], testable on the JVM. */
object PairingTokens {

    const val PAIR_URI_PREFIX = "tapandplay://pair"

    /** Enough for two parents plus a reinstalled companion or two; the oldest is forgotten first. */
    const val MAX_TOKENS = 4

    private val TOKEN = Regex("[A-Za-z0-9_-]{32,128}")

    /** The token in a `tapandplay://pair?token=...` URI, or null if [uri] isn't a valid pairing URI. */
    fun tokenFrom(uri: String): String? {
        val parsed = try {
            URI(uri.trim())
        } catch (e: Exception) {
            return null
        }
        if (!parsed.scheme.equals("tapandplay", ignoreCase = true) || !parsed.host.equals("pair", ignoreCase = true)) {
            return null
        }
        val token = parsed.rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("token=") }
            ?.removePrefix("token=")
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?: return null
        return token.takeIf { TOKEN.matches(it) }
    }

    /** Newest first, no duplicates, at most [MAX_TOKENS]. */
    fun add(tokens: List<String>, token: String): List<String> =
        (listOf(token) + tokens.filter { it != token }).take(MAX_TOKENS)

    /** The token in an `Authorization: Bearer <token>` header. */
    fun bearer(header: String?): String? {
        val value = header?.trim() ?: return null
        if (!value.startsWith("Bearer ", ignoreCase = true)) return null
        return value.substring(7).trim().ifEmpty { null }
    }

    /** Constant-time per token, and every token is checked, so timing doesn't reveal which one matched. */
    fun matches(tokens: List<String>, candidate: String?): Boolean {
        if (candidate == null) return false
        val bytes = candidate.toByteArray()
        var found = false
        for (token in tokens) {
            found = MessageDigest.isEqual(token.toByteArray(), bytes) or found
        }
        return found
    }

    fun encode(tokens: List<String>): String = tokens.joinToString("\n")

    fun decode(text: String?): List<String> =
        text.orEmpty().split('\n').filter { TOKEN.matches(it) }.take(MAX_TOKENS)
}
