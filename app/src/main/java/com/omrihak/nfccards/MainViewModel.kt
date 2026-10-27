package com.omrihak.nfccards

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import com.omrihak.nfccards.content.ContentException
import com.omrihak.nfccards.content.ContentResolver
import com.omrihak.nfccards.content.ErrorKind
import com.omrihak.nfccards.content.ResolvedContent
import com.omrihak.nfccards.media.MediaCache
import com.omrihak.nfccards.media.PlayerController
import com.omrihak.nfccards.remote.Pairing
import com.omrihak.nfccards.remote.Remote
import com.omrihak.nfccards.remote.RemoteApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

sealed interface UiState {
    data object Idle : UiState
    /** Fetching a card's content. Shown as the idle screen, animated faster. */
    data object Loading : UiState
    data class Audio(val artwork: Bitmap?) : UiState
    data object Video : UiState
}

/**
 * Ties it together: card URI in, UI state out. All state changes happen on the main thread.
 * NFC knows nothing about media; the UI only renders [state].
 * The parent's companion app drives the same functions over Wi-Fi (see [Remote]).
 */
class MainViewModel(app: Application) : AndroidViewModel(app), RemoteApi.Player {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolver = ContentResolver(MediaCache(File(app.filesDir, "media")), appScope)

    val player = PlayerController(app, object : PlayerController.Listener {
        override fun onEnded() = onPlaybackEnded()
        override fun onError(error: PlaybackException) = onPlaybackError(error)
    })

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Incremented whenever something went wrong, so the idle screen can give a small wordless wobble. */
    private val _problems = MutableStateFlow(0)
    val problems: StateFlow<Int> = _problems.asStateFlow()

    /** Why a card failed, for developers. Only shown (as a toast) in debug builds. */
    private val _debugMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val debugMessages: SharedFlow<String> = _debugMessages.asSharedFlow()

    /** Incremented on each pairing card, so the idle screen can give a small happy bounce. */
    private val _pairings = MutableStateFlow(0)
    val pairings: StateFlow<Int> = _pairings.asStateFlow()

    /** Admin card URIs sent by the companion app; the activity handles them like the cards. */
    private val _adminCards = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val adminCards: SharedFlow<String> = _adminCards.asSharedFlow()

    private var loadJob: Job? = null
    private var currentCard: String? = null
    private var currentMediaUrl: String? = null
    private var currentTitle: String? = null

    private val pairing = Pairing(app)
    private val remote = Remote(app, this, pairing, { _adminCards.tryEmit(it) }, viewModelScope).also { it.start() }

    /** A pairing card (`tapandplay://pair?token=...`) lets a companion app in. False for any other card. Main thread. */
    fun onPairingCard(uri: String): Boolean {
        if (!pairing.onCard(uri)) return false
        Log.i(TAG, "Paired a companion app")
        _pairings.value += 1
        return true
    }

    /** [uri] is what the NFC reader found on the card; null if the card had no NDEF URI. Any thread. */
    fun onCardScanned(uri: String?) {
        viewModelScope.launch { handleScan(uri) }
    }

    private fun handleScan(uri: String?) {
        if (uri == null) {
            Log.w(TAG, "Unknown card: no NDEF URI")
            _debugMessages.tryEmit("${ErrorKind.UNKNOWN_CARD}: no NDEF URI on the tag")
            problem()
            return
        }
        val card = uri.trim()

        val state = _state.value
        val alreadyActive = card == currentCard &&
            (state is UiState.Loading || state is UiState.Audio || state is UiState.Video)
        if (alreadyActive) {
            Log.d(TAG, "Same card scanned again while active; ignoring")
            return
        }

        loadJob?.cancel()
        player.stop()
        currentCard = card
        currentMediaUrl = null
        currentTitle = null
        _state.value = UiState.Loading

        loadJob = viewModelScope.launch {
            try {
                val content = resolver.resolve(card)
                currentTitle = content.title
                when (content) {
                    is ResolvedContent.Audio -> {
                        currentMediaUrl = content.mediaUrl
                        _state.value = UiState.Audio(content.artwork)
                        player.play(content.file)
                    }
                    is ResolvedContent.Video -> {
                        currentMediaUrl = content.mediaUrl
                        _state.value = UiState.Video
                        player.play(content.file)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ContentException) {
                Log.w(TAG, "Card $card failed: ${e.kind}: ${e.message}", e.cause ?: e)
                _debugMessages.tryEmit("${e.kind}: ${e.message}")
                resetToIdle()
            } catch (e: Exception) {
                Log.e(TAG, "Card $card failed unexpectedly", e)
                _debugMessages.tryEmit("UNEXPECTED: $e")
                resetToIdle()
            }
        }
    }

    private fun onPlaybackEnded() {
        if (_state.value is UiState.Audio || _state.value is UiState.Video) {
            Log.i(TAG, "Playback finished")
            stop()
        }
    }

    /** Hold (4 s), or the companion app: stop whatever is playing and go back to the idle screen. */
    override fun stop() {
        loadJob?.cancel()
        player.stop()
        currentCard = null
        currentMediaUrl = null
        currentTitle = null
        _state.value = UiState.Idle
    }

    // ---- Companion app (RemoteApi.Player); called on the main thread ----

    override fun play(cardUrl: String) = handleScan(cardUrl)

    override fun pause() = player.pause()

    override fun resume() = player.resume()

    override fun status(): JSONObject {
        val json = JSONObject()
        val state = _state.value
        json.put(
            "state",
            when (state) {
                UiState.Idle -> "idle"
                UiState.Loading -> "loading"
                is UiState.Audio -> "audio"
                UiState.Video -> "video"
            },
        )
        json.put("paused", (state is UiState.Audio || state is UiState.Video) && !player.playWhenReady)
        if (state != UiState.Idle) {
            json.put("card", currentCard)
            currentTitle?.let { json.put("title", it) }
        }
        if (state is UiState.Audio || state is UiState.Video) {
            json.put("positionMs", player.player.currentPosition)
            val duration = player.player.duration
            if (duration != C.TIME_UNSET) json.put("durationMs", duration)
        }
        return json
    }

    private fun onPlaybackError(error: PlaybackException) {
        Log.w(TAG, "Playback failed (${ErrorKind.UNSUPPORTED}): ${error.errorCodeName}", error)
        _debugMessages.tryEmit("${ErrorKind.UNSUPPORTED}: playback failed (${error.errorCodeName})")
        // The cached file may be corrupt: drop it so the next tap downloads it again.
        currentMediaUrl?.let { resolver.evict(it) }
        resetToIdle()
    }

    private fun resetToIdle() {
        player.stop()
        currentCard = null
        currentMediaUrl = null
        currentTitle = null
        _state.value = UiState.Idle
        problem()
    }

    private fun problem() {
        _problems.value += 1
    }

    /** Activity left the foreground (screen off, another app): don't keep playing in the dark. */
    fun onBackground() = player.pause()

    override fun onCleared() {
        remote.stop()
        loadJob?.cancel()
        player.release()
        appScope.cancel()
    }

    private companion object {
        const val TAG = "MainViewModel"
    }
}
