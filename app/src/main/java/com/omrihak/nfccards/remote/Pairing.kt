package com.omrihak.nfccards.remote

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * The parent phones allowed to use the remote control. A companion app pairs once by showing the
 * player its secret token as a QR code (or on a sticker): `tapandplay://pair?token=<token>`.
 * Afterwards it sends the token with every request. Only someone holding the player can pair.
 * A companion that made the kiosk setup QR code is paired from the start: the token rides along in
 * the QR code's admin extras ([onProvisioned]).
 */
class Pairing(context: Context) {

    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    /**
     * Read from the preferences every time (they are cached in memory), so a token stored by
     * [onProvisioned] in a receiver is seen by the server that is already running.
     */
    val tokens: List<String> get() = PairingTokens.decode(prefs.getString(KEY_TOKENS, null))

    /** Remembers the token of a pairing card. Returns false if [uri] isn't one. Main thread. */
    fun onCard(uri: String): Boolean = add(PairingTokens.tokenFrom(uri))

    /**
     * Remembers the token a companion put in the setup QR code, if there is one. [intent] is any
     * provisioning intent that carries the admin extras. Returns true if it paired.
     */
    fun onProvisioned(intent: Intent?): Boolean {
        val key = DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
        val extras: PersistableBundle? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(key, PersistableBundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(key)
        }
        return add(extras?.getString(PairingTokens.PROVISIONING_TOKEN_KEY)?.takeIf(PairingTokens::isValid))
    }

    @Synchronized
    private fun add(token: String?): Boolean {
        if (token == null) return false
        // commit, not apply: a receiver's process may be gone right after it returns.
        prefs.edit().putString(KEY_TOKENS, PairingTokens.encode(PairingTokens.add(tokens, token))).commit()
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

    /** The key of the token in the setup QR code's admin extras bundle (companion's ProvisioningQr). */
    const val PROVISIONING_TOKEN_KEY = "pair_token"

    private val TOKEN = Regex("[A-Za-z0-9_-]{32,128}")

    fun isValid(token: String) = TOKEN.matches(token)

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
        return token.takeIf(::isValid)
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
