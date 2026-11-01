package com.omrihak.nfccards.game

import com.omrihak.nfccards.speech.Speaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Plays a recording instead of synthesized speech. */
fun interface ClipPlayer {
    /** Plays the recording at [url] and returns true when it has finished; false if it is not available. */
    suspend fun play(url: String): Boolean
}

/**
 * Carries out what a [Game] asks for, in order: speaks, shows hints and stars, waits for a tap. A new tap
 * cuts off whatever is being said, so the game answers at once. If nothing is tapped for [idleMillis] the
 * question is repeated; after [maxIdleRepeats] repeats the child has wandered off and the game ends quietly.
 *
 * Callbacks run on [scope]'s dispatcher (the main thread in the app).
 */
class GameSession(
    private val game: Game,
    private val speaker: Speaker,
    private val clips: ClipPlayer,
    private val scope: CoroutineScope,
    private val listener: Listener,
    private val idleMillis: Long = DEFAULT_IDLE_MILLIS,
    private val maxIdleRepeats: Int = DEFAULT_IDLE_REPEATS,
) {
    interface Listener {
        /** Show the picture of this card (a [cardKey] from the deck), or hide the hint when null. */
        fun onHint(cardKey: String?)
        fun onStar()
        /** The game is over, or the child left. */
        fun onEnded()
        /** Something stopped the game, e.g. there is no voice for the language. */
        fun onProblem(error: Exception)
    }

    private var job: Job? = null

    fun start() = run(game.start())

    /** [cardKey] as made by [CardKey.of]. */
    fun onCard(cardKey: String) = run(game.onCard(cardKey))

    fun cancel() {
        job?.cancel()
        job = null
    }

    private fun run(actions: List<GameAction>) {
        job?.cancel()
        job = scope.launch {
            try {
                execute(actions)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listener.onProblem(e)
            }
        }
    }

    private suspend fun execute(first: List<GameAction>) {
        var actions = first
        var idleRepeats = 0
        while (true) {
            var waiting = false
            for (action in actions) {
                when (action) {
                    is GameAction.Say -> say(action)
                    is GameAction.ShowHint -> listener.onHint(action.cardUrl)
                    GameAction.AwardStar -> {
                        listener.onHint(null)
                        listener.onStar()
                    }
                    GameAction.WaitForCard -> waiting = true
                    GameAction.Finish -> {
                        listener.onEnded()
                        return
                    }
                }
                if (waiting) break
            }
            if (!waiting) return
            delay(idleMillis)
            if (++idleRepeats > maxIdleRepeats) {
                listener.onEnded()
                return
            }
            actions = game.onIdle()
        }
    }

    private suspend fun say(action: GameAction.Say) {
        if (action.audio != null && clips.play(action.audio)) return
        speaker.speak(action.text, action.lang)
    }

    companion object {
        const val DEFAULT_IDLE_MILLIS = 12_000L
        const val DEFAULT_IDLE_REPEATS = 3
    }
}
