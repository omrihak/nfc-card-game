package com.omrihak.nfccards.companion.cards

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Progress(val text: String, val fraction: Float)

/** The Cards tab: the parent's cards in Google Drive, making new ones and removing old ones. */
class CardsViewModel(app: Application) : AndroidViewModel(app) {

    val auth = GoogleAuth(app)
    private val store = CardStore(Drive { auth.token() })
    private val prefs = app.getSharedPreferences("cards", Context.MODE_PRIVATE)

    /** Null while it isn't known yet (checking a previous sign-in). */
    private val _signedIn = MutableStateFlow<Boolean?>(if (prefs.getBoolean(KEY_SIGNED_IN, false)) null else false)
    val signedIn: StateFlow<Boolean?> = _signedIn.asStateFlow()

    private val _cards = MutableStateFlow<List<Card>>(emptyList())
    val cards: StateFlow<List<Card>> = _cards.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** While a card is being created. */
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        if (_signedIn.value == null) refresh()
    }

    /** After the consent screen (or a silent authorization) succeeded. */
    fun onSignedIn() {
        prefs.edit().putBoolean(KEY_SIGNED_IN, true).apply()
        _signedIn.value = true
        refresh()
    }

    fun onSignInFailed(error: Throwable) {
        _signedIn.value = false
        _message.value = "Google sign-in failed: ${error.message ?: error.javaClass.simpleName}"
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            attempt { _cards.value = store.cards().asReversed() }
            _loading.value = false
        }
    }

    fun create(name: String, type: String, media: Uri, image: Uri?, onDone: () -> Unit) {
        if (_progress.value != null) return
        viewModelScope.launch {
            _progress.value = Progress("Starting…", 0f)
            val ok = attempt {
                val card = store.create(name, type, upload(media), image?.let { upload(it) }) { text, fraction ->
                    _progress.value = Progress(text, fraction.coerceIn(0f, 1f))
                }
                _message.value = "\"${card.name}\" is ready. Write it to a sticker, or play it on the player."
                _cards.value = store.cards().asReversed()
            }
            _progress.value = null
            if (ok) onDone()
        }
    }

    fun remove(card: Card) {
        viewModelScope.launch {
            _loading.value = true
            attempt {
                store.remove(card)
                _cards.value = store.cards().asReversed()
                _message.value = "Removed \"${card.name}\". Its folder is in the Drive trash for 30 days."
            }
            _loading.value = false
        }
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Runs a Drive action; reports failures as a message. Returns whether it succeeded. */
    private suspend fun attempt(action: suspend () -> Unit): Boolean = try {
        action()
        _signedIn.value = true
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: GoogleAuth.NeedsSignIn) {
        _signedIn.value = false
        false
    } catch (e: Exception) {
        if (_signedIn.value == null) _signedIn.value = true
        _message.value = e.message ?: e.javaClass.simpleName
        false
    }

    /** A picked file as an upload. Copies it to the cache first if its size is unknown (Drive needs it up front). */
    private suspend fun upload(uri: Uri): CardStore.Upload = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val type = resolver.getType(uri) ?: "application/octet-stream"
        val fileName = name ?: uri.lastPathSegment ?: "file"
        if (size >= 0) {
            CardStore.Upload(fileName, UploadSource(size, type) { resolver.openInputStream(uri) ?: error("Cannot open $fileName") })
        } else {
            val copy = File.createTempFile("upload", null, getApplication<Application>().cacheDir)
            resolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                ?: error("Cannot open $fileName")
            copy.deleteOnExit()
            CardStore.Upload(fileName, UploadSource(copy.length(), type) { copy.inputStream() })
        }
    }

    private companion object {
        const val KEY_SIGNED_IN = "signedIn"
    }
}
