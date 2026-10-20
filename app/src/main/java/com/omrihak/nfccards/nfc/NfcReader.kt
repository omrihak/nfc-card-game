package com.omrihak.nfccards.nfc

import android.app.Activity
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Parcelable
import android.util.Log

/**
 * Read-only NFC. Uses reader mode while the activity is in the foreground: cards are read
 * without the system's tag dispatch, chooser dialogs, or tag sounds.
 *
 * Only emits the URI found on the card (or null for a card that has no NDEF URI).
 * It knows nothing about what the URI means.
 */
class NfcReader(
    private val activity: Activity,
    private val onCard: (uri: String?) -> Unit,
) : NfcAdapter.ReaderCallback {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    fun start() {
        val a = adapter
        when {
            a == null -> Log.e(TAG, "This device has no NFC")
            !a.isEnabled -> Log.e(TAG, "NFC is turned off in system settings")
        }
        a?.enableReaderMode(
            activity,
            this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null,
        )
    }

    fun stop() {
        adapter?.disableReaderMode(activity)
    }

    /** Called on a binder thread. */
    override fun onTagDiscovered(tag: Tag) {
        val uri = try {
            Ndef.get(tag)?.let { ndef -> uriFrom(ndef.cachedNdefMessage ?: readMessage(ndef)) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read tag", e)
            null
        }
        Log.i(TAG, "Tag ${tag.id.joinToString("") { "%02x".format(it) }} -> ${uri ?: "(no NDEF URI)"}")
        onCard(uri)
    }

    private fun readMessage(ndef: Ndef): NdefMessage? = ndef.use {
        it.connect()
        it.ndefMessage
    }

    companion object {
        private const val TAG = "NfcReader"

        /** First URI record in the message (handles well-known URI and absolute-URI records). */
        fun uriFrom(message: NdefMessage?): String? =
            message?.records?.firstNotNullOfOrNull { it.toUri()?.toString() }

        /** For when a tap launches the app from closed (NDEF_DISCOVERED intent). */
        fun uriFrom(intent: Intent): String? {
            val raw: Array<out Parcelable>? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, Parcelable::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            }
            return raw?.filterIsInstance<NdefMessage>()?.firstNotNullOfOrNull { uriFrom(it) }
        }
    }
}
