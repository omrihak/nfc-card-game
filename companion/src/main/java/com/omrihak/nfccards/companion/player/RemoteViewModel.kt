package com.omrihak.nfccards.companion.player

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Connection {
    /** No player known yet; looking for one on the Wi-Fi. */
    data object Searching : Connection
    data class Connected(val name: String, val address: String) : Connection
    /** A player was known but doesn't answer: it (or this phone) is off the Wi-Fi, or the app is closed. */
    data class Unreachable(val name: String, val address: String) : Connection
}

/**
 * The Player tab: finds the player, polls its status about once a second while the app is on
 * screen, and sends it commands.
 */
class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private val finder = PlayerFinder(app) { found -> viewModelScope.launch { onFound(found) } }

    private val _connection = MutableStateFlow<Connection>(
        prefs.getString(KEY_ADDRESS, null)
            ?.let { Connection.Unreachable(prefs.getString(KEY_NAME, null) ?: it, it) }
            ?: Connection.Searching,
    )
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    private val _status = MutableStateFlow<PlayerStatus?>(null)
    val status: StateFlow<PlayerStatus?> = _status.asStateFlow()

    /** The last failed command, shown briefly. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var client: PlayerClient? = prefs.getString(KEY_ADDRESS, null)?.let { PlayerClient(it) }
    private var pollJob: Job? = null

    /** Polls and listens for players only while the app is on screen. */
    fun setActive(active: Boolean) {
        if (active) {
            finder.start()
            if (pollJob == null) pollJob = viewModelScope.launch { poll() }
        } else {
            finder.stop()
            pollJob?.cancel()
            pollJob = null
        }
    }

    private suspend fun poll() {
        var failures = 0
        while (viewModelScope.isActive) {
            val c = client
            if (c != null) {
                try {
                    _status.value = c.status()
                    failures = 0
                    markConnected()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    if (failures >= 2) markUnreachable()
                }
            }
            delay(if (failures == 0) POLL_MILLIS else RETRY_MILLIS)
        }
    }

    private fun onFound(found: FoundPlayer) {
        val current = _connection.value
        // Keep a player that answers; switch when the remembered one is gone (e.g. it got a new IP address).
        if (current is Connection.Connected && current.address != found.address) return
        use(found.name, found.address)
    }

    /** Typed in by the parent, when discovery doesn't work on their network. */
    fun connectManually(input: String) {
        val trimmed = input.trim().removePrefix("http://").trimEnd('/')
        if (trimmed.isEmpty()) return
        val address = if (':' in trimmed.substringAfterLast(']')) trimmed else "$trimmed:$DEFAULT_PORT"
        use(address, address)
    }

    private fun use(name: String, address: String) {
        prefs.edit().putString(KEY_ADDRESS, address).putString(KEY_NAME, name).apply()
        client = PlayerClient(address)
        _connection.value = Connection.Unreachable(name, address)
        // Check now rather than waiting for the next poll.
        viewModelScope.launch {
            try {
                _status.value = PlayerClient(address).status()
                markConnected()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The poll keeps trying.
            }
        }
    }

    private fun markConnected() {
        val c = _connection.value
        val address = client?.address ?: return
        val name = when (c) {
            is Connection.Connected -> c.name
            is Connection.Unreachable -> c.name
            Connection.Searching -> address
        }
        _connection.value = Connection.Connected(name, address)
    }

    private fun markUnreachable() {
        val address = client?.address ?: return
        val name = (_connection.value as? Connection.Connected)?.name
            ?: (_connection.value as? Connection.Unreachable)?.name
            ?: address
        _connection.value = Connection.Unreachable(name, address)
        _status.value = null
    }

    fun play(cardUrl: String) = launchCommand { play(cardUrl) }
    fun pause() = launchCommand { pause() }
    fun resume() = launchCommand { resume() }
    fun stop() = launchCommand { stop() }
    fun setVolume(level: Int) = launchCommand { setVolume(level) }
    fun stepVolume(step: Int) = launchCommand { stepVolume(step) }

    fun clearError() {
        _error.value = null
    }

    private fun launchCommand(action: suspend PlayerClient.() -> PlayerStatus) {
        viewModelScope.launch { command(action) }
    }

    private suspend fun command(action: suspend PlayerClient.() -> PlayerStatus) {
        val c = client ?: run {
            _error.value = "No player found yet. Is it on the same Wi-Fi?"
            return
        }
        try {
            _status.value = c.action()
            markConnected()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = "The player didn't answer (${e.message ?: e.javaClass.simpleName}). " +
                "Both phones need to be on the same Wi-Fi, with the player app open."
            markUnreachable()
        }
    }

    override fun onCleared() {
        finder.stop()
    }

    private companion object {
        const val KEY_ADDRESS = "address"
        const val KEY_NAME = "name"
        const val DEFAULT_PORT = 8765
        const val POLL_MILLIS = 1_000L
        const val RETRY_MILLIS = 3_000L
    }
}
