package com.omrihak.nfccards.companion.cards

import android.app.Activity
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException

/**
 * Writes a card's link to an NTAG215 sticker as a URI record, with this phone's own NFC
 * (what the web manager does with Web NFC). Reader mode is on only while a write is waiting.
 */
class StickerWriter(private val activity: Activity) : NfcAdapter.ReaderCallback {

    sealed interface State {
        data object Idle : State
        data class Waiting(val card: Card) : State
        data class Done(val card: Card) : State
        data class Failed(val card: Card, val reason: String) : State
    }

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private var resumed = false

    /** Observable by Compose. */
    var state: State by mutableStateOf(State.Idle)
        private set

    val isAvailable get() = adapter != null

    fun begin(card: Card) {
        state = when {
            adapter == null -> State.Failed(card, "This phone has no NFC.")
            !adapter.isEnabled -> State.Failed(card, "NFC is off. Turn it on in Settings, then try again.")
            else -> State.Waiting(card)
        }
        updateReaderMode()
    }

    fun dismiss() {
        state = State.Idle
        updateReaderMode()
    }

    fun onResume() {
        resumed = true
        updateReaderMode()
    }

    fun onPause() {
        resumed = false
        updateReaderMode()
    }

    private fun updateReaderMode() {
        val a = adapter ?: return
        if (resumed && state is State.Waiting) {
            a.enableReaderMode(
                activity,
                this,
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
                null,
            )
        } else if (resumed) {
            a.disableReaderMode(activity)
        }
    }

    /** Binder thread. */
    override fun onTagDiscovered(tag: Tag) {
        val card = (state as? State.Waiting)?.card ?: return
        val message = NdefMessage(NdefRecord.createUri(card.contentUrl))
        val result = try {
            write(tag, message)
            State.Done(card)
        } catch (e: Exception) {
            Log.w(TAG, "Sticker write failed", e)
            State.Failed(card, e.message ?: "Could not write the sticker. Hold it still and try again.")
        }
        activity.runOnUiThread {
            state = result
            updateReaderMode()
        }
    }

    private fun write(tag: Tag, message: NdefMessage) {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.use {
                it.connect()
                if (!it.isWritable) throw IOException("This sticker is locked (read-only).")
                if (it.maxSize < message.byteArrayLength) {
                    throw IOException("The link doesn't fit on this sticker (${it.maxSize} bytes). Use an NTAG215.")
                }
                it.writeNdefMessage(message)
            }
            return
        }
        val formatable = NdefFormatable.get(tag) ?: throw IOException("This tag can't hold a link. Use an NTAG215 sticker.")
        formatable.use {
            it.connect()
            try {
                it.format(message)
            } catch (e: FormatException) {
                throw IOException("Could not format the sticker.", e)
            }
        }
    }

    private companion object {
        const val TAG = "StickerWriter"
    }
}
