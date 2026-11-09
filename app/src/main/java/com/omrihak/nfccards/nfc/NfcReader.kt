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
 * Read-only NFC. Uses reader mode while the activity is in the foreground: cards are read without
 * the system's tag dispatch, chooser dialogs, or the tag sound. A tap that launches the app while it
 * is closed comes as an intent instead; the activity passes it to [handle].
 *
 * Some phones (Samsung) don't poll for tags at all while a camera is open: see MainActivity's
 * QR scanning switch.
 *
 * Only emits the URI found on the card (or null for a card that has no NDEF URI).
 * It knows nothing about what the URI means.
 */
class NfcReader(
    private val activity: Activity,
    private val onCard: (uri: String?) -> Unit,
) : NfcAdapter.ReaderCallback {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    /** Call from onResume. */
    fun start() {
        val a = adapter
        when {
            a == null -> { Log.e(TAG, "This device has no NFC"); return }
            !a.isEnabled -> Log.e(TAG, "NFC is turned off in system settings")
        }
        try {
            a!!.enableReaderMode(
                activity,
                this,
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                null,
            )
            Log.i(TAG, "Listening for cards")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start NFC", e)
        }
    }

    /** Call from onPause. */
    fun stop() {
        try {
            adapter?.disableReaderMode(activity)
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop NFC", e)
        }
    }

    /** Reader mode: called on a binder thread. */
    override fun onTagDiscovered(tag: Tag) {
        val uri = try {
            Ndef.get(tag)?.let { ndef -> uriFrom(ndef.cachedNdefMessage ?: readMessage(ndef)) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read tag", e)
            null
        }
        Log.i(TAG, "Tag ${tag.id.joinToString("") { "%02x".format(it) }} -> ${uri ?: "(no NDEF URI)"}")
        activity.runOnUiThread { onCard(uri) }
    }

    private fun readMessage(ndef: Ndef): NdefMessage? = ndef.use {
        it.connect()
        it.ndefMessage
    }

    /** A tag intent (one that launched the app, or came while it was opening): emits its URI. Returns false for any other intent. */
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
