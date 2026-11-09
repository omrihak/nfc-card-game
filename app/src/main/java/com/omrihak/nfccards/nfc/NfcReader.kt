package com.omrihak.nfccards.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Parcelable
import android.util.Log

/**
 * Read-only NFC. While the activity is in the foreground, every tag is sent to it (foreground
 * dispatch): Android reads the tag and delivers it to the activity's onNewIntent, which passes it
 * to [handle]. This is the same path that opens the app from a tap when it is closed.
 *
 * (It used reader mode before. On some phones reader mode never saw a card while the app was
 * open, though the same card read fine from Settings through the system dispatch.)
 *
 * Only emits the URI found on the card (or null for a card that has no NDEF URI).
 * It knows nothing about what the URI means.
 */
class NfcReader(
    private val activity: Activity,
    private val onCard: (uri: String?) -> Unit,
) {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    /** Call from onResume. */
    fun start() {
        val a = adapter
        when {
            a == null -> { Log.e(TAG, "This device has no NFC"); return }
            !a.isEnabled -> Log.e(TAG, "NFC is turned off in system settings")
        }
        val intent = Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Mutable: Android fills in the tag extras.
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getActivity(activity, 0, intent, flags)
        try {
            // No filters: every tag comes here while the app is open.
            a!!.enableForegroundDispatch(activity, pending, null, null)
            Log.i(TAG, "Listening for cards")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start NFC", e)
        }
    }

    /** Call from onPause. */
    fun stop() {
        try {
            adapter?.disableForegroundDispatch(activity)
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop NFC", e)
        }
    }

    /** A tag intent (from the foreground dispatch, or one that launched the app): emits its URI. Returns false for any other intent. */
    fun handle(intent: Intent): Boolean {
        if (intent.action !in TAG_ACTIONS) return false
        val uri = try {
            uriFrom(intent) ?: intent.dataString
        } catch (e: Exception) {
            Log.w(TAG, "Could not read tag", e)
            null
        }
        val id = intent.getByteArrayExtra(NfcAdapter.EXTRA_ID)?.joinToString("") { "%02x".format(it) }
        Log.i(TAG, "Tag $id (${intent.action}) -> ${uri ?: "(no NDEF URI)"}")
        onCard(uri)
        return true
    }

    companion object {
        private const val TAG = "NfcReader"

        private val TAG_ACTIONS = setOf(
            NfcAdapter.ACTION_NDEF_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_TAG_DISCOVERED,
        )

        /** First URI record in the message (handles well-known URI and absolute-URI records). */
        fun uriFrom(message: NdefMessage?): String? =
            message?.records?.firstNotNullOfOrNull { it.toUri()?.toString() }

        /** The URI in a tag intent: the NDEF messages Android already read, else the tag's cached message. */
        fun uriFrom(intent: Intent): String? {
            val raw: Array<out Parcelable>? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, Parcelable::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            }
            raw?.filterIsInstance<NdefMessage>()?.firstNotNullOfOrNull { uriFrom(it) }?.let { return it }

            val tag: Tag? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
            return tag?.let { Ndef.get(it)?.cachedNdefMessage }?.let { uriFrom(it) }
        }
    }
}
