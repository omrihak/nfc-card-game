package com.omrihak.nfccards

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import com.omrihak.nfccards.content.ContentException
import com.omrihak.nfccards.content.ContentResolver
import com.omrihak.nfccards.content.ErrorKind
import com.omrihak.nfccards.content.ResolvedContent
import com.omrihak.nfccards.media.MediaCache
import com.omrihak.nfccards.media.PlayerController
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
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

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

    private var loadJob: Job? = null
    private var currentCard: String? = null
    private var currentMediaUrl: String? = null
    private var audioFinished = false

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
            (state is UiState.Loading || state is UiState.Video || (state is UiState.Audio && !audioFinished))
        if (alreadyActive) {
            Log.d(TAG, "Same card scanned again while active; ignoring")
            return
        }

        loadJob?.cancel()
        player.stop()
        currentCard = card
        currentMediaUrl = null
        audioFinished = false
        _state.value = UiState.Loading

        loadJob = viewModelScope.launch {
            try {
                when (val content = resolver.resolve(card)) {
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
        when (_state.value) {
            is UiState.Video -> {
                Log.i(TAG, "Video finished")
                player.stop()
                currentCard = null
                _state.value = UiState.Idle
            }
            is UiState.Audio -> {
                // Stay on the artwork; tapping the screen replays.
                audioFinished = true
            }
            else -> Unit
        }
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
        _state.value = UiState.Idle
        problem()
    }

    private fun problem() {
        _problems.value += 1
    }

    /** Activity left the foreground (screen off, another app): don't keep playing in the dark. */
    fun onBackground() = player.pause()

    override fun onCleared() {
        loadJob?.cancel()
        player.release()
        appScope.cancel()
    }

    private companion object {
        const val TAG = "MainViewModel"
    }
}
