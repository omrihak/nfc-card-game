package com.omrihak.nfccards.game

import com.omrihak.nfccards.content.ContentException
import com.omrihak.nfccards.content.ErrorKind
import com.omrihak.nfccards.content.GameSpec
import com.omrihak.nfccards.content.Words
import kotlin.random.Random

/** A card of the game's deck, as far as the game cares: which card it is and how it is spoken. */
data class GameCard(val url: String, val words: Words)

/** What a game asks the app to do. Actions run in order; the app does not need to understand the game. */
sealed interface GameAction {
    /**
     * Speak [text] in language [lang]; if [audio] (an http link) is given, play that recording instead.
     * Speech is queued: a later action takes effect only after the earlier speech has been spoken,
     * except [AwardStar] and [ShowHint], which show at once.
     */
    data class Say(val text: String, val lang: String, val audio: String? = null) : GameAction

    /** Show the picture of this card (a hint). It stays until the next [AwardStar]. */
    data class ShowHint(val cardUrl: String) : GameAction

    /** The child earned a star. */
    data object AwardStar : GameAction

    /** Everything is said; now wait for a card tap. If nothing happens for a while the app calls [Game.onIdle]. */
    data object WaitForCard : GameAction

    /** The game is over (after the speech before it): go back to the idle screen. */
    data object Finish : GameAction
}

/**
 * One kind of game. No Android in here: the app feeds events in and carries out the returned actions,
 * so a game is easy to test with a plain list of cards. One instance is one play of the game.
 */
interface Game {
    /** The game card was tapped. */
    fun start(): List<GameAction>

    /** The child tapped a card ([url] is the link on it); may be a card that is not in the deck. */
    fun onCard(url: String): List<GameAction>

    /** Nothing was tapped for a while after [GameAction.WaitForCard]. */
    fun onIdle(): List<GameAction>
}

/** Maps the `game` field of a game card to its implementation. */
object Games {
    /**
     * @param cards the deck's cards, in deck order; cards that cannot be spoken in the game's language are skipped.
     * @throws ContentException if the game is unknown or the deck cannot make a game.
     */
    fun create(spec: GameSpec, cards: List<GameCard>, random: Random = Random.Default): Game = when (spec.game) {
        FindCardGame.ID -> FindCardGame.create(spec, cards, random)
        else -> throw ContentException(ErrorKind.UNSUPPORTED, "Unsupported game \"${spec.game}\"")
    }
}
