package com.omrihak.nfccards.game

import com.omrihak.nfccards.content.ContentException
import com.omrihak.nfccards.content.ErrorKind
import com.omrihak.nfccards.content.GameLanguage
import com.omrihak.nfccards.content.GameSpec
import com.omrihak.nfccards.content.WordForm
import kotlin.random.Random

/**
 * "Where is the dog?" The game names a card; the child finds it among the cards in front of her and taps it.
 *
 * - Right: a star, the word is said, next round. After the last round the game finishes.
 * - Wrong (any other card, even one from outside the deck): a gentle correction and the question again.
 *   No penalty. From the second miss on the card's picture is shown as a hint.
 * - Nothing tapped: the question is repeated ([onIdle]).
 *
 * The deck is the set of cards on the floor, so its size is how many choices she has. A target does not repeat
 * until every card has been asked once.
 */
class FindCardGame private constructor(
    private val language: GameLanguage,
    private val phrases: Phrases,
    private val cards: List<GameCard>,
    private val rounds: Int,
    private val random: Random,
) : Game {

    private val bag = ArrayDeque<GameCard>()
    private lateinit var target: GameCard
    private var round = 0
    private var misses = 0
    private var over = false
    private var lastTarget: GameCard? = null

    override fun start(): List<GameAction> {
        round = 0
        over = false
        nextTarget()
        return buildList {
            add(say(phrases.intro))
            addAll(question())
        }
    }

    override fun onCard(url: String): List<GameAction> {
        if (over) return emptyList()
        if (url == target.url) return correct()

        misses++
        val tapped = cards.firstOrNull { it.url == url }
        return buildList {
            val word = tapped?.words?.get(language.ask)
            add(say(if (word != null) Phrases.fill(phrases.wrong, word.forQuestion) else phrases.tryAgain))
            if (misses >= HINT_AFTER_MISSES) add(GameAction.ShowHint(target.url))
            addAll(question())
        }
    }

    override fun onIdle(): List<GameAction> = if (over) emptyList() else question()

    private fun correct(): List<GameAction> {
        val asked = target.words.getValue(language.ask)
        val taught = target.words.getValue(language.answer)
        round++
        return buildList {
            add(GameAction.AwardStar)
            add(say(Phrases.fill(phrases.correct, asked.text), asked.audio))
            // Learning mode (ask in Hebrew, teach English): after the Hebrew cheer, say the English word.
            if (language.answer != language.ask) add(GameAction.Say(taught.text, language.answer, taught.audio))
            if (round >= rounds) {
                over = true
                add(say(phrases.finish))
                add(GameAction.Finish)
            } else {
                nextTarget()
                addAll(question())
            }
        }
    }

    private fun question(): List<GameAction> {
        val word = target.words.getValue(language.ask)
        return listOf(say(Phrases.fill(phrases.ask, word.forQuestion), word.audio), GameAction.WaitForCard)
    }

    private fun say(text: String, audio: String? = null) = GameAction.Say(text, language.ask, audio)

    private fun nextTarget() {
        if (bag.isEmpty()) refill()
        target = bag.removeFirst()
        lastTarget = target
        misses = 0
    }

    /** A fresh shuffle; the card just asked does not come first again. */
    private fun refill() {
        val shuffled = cards.shuffled(random).toMutableList()
        val last = lastTarget
        if (last != null && shuffled.size > 1 && shuffled.first() == last) {
            shuffled.add(shuffled.removeAt(0))
        }
        bag.addAll(shuffled)
    }

    companion object {
        const val ID = "find-card"
        const val DEFAULT_ROUNDS = 5
        const val HINT_AFTER_MISSES = 2

        fun create(spec: GameSpec, deck: List<GameCard>, random: Random): FindCardGame {
            val language = spec.lang
            val playable = deck.filter { card ->
                card.words[language.ask] != null && card.words[language.answer] != null
            }
            if (playable.size < 2) {
                throw ContentException(
                    ErrorKind.UNSUPPORTED,
                    "Find the card needs at least 2 cards with words in ${language.ask}" +
                        (if (language.answer != language.ask) " and ${language.answer}" else "") +
                        ", found ${playable.size}",
                )
            }
            val phrases = Phrases.forLanguage(language.ask, spec.say)
            val rounds = spec.rounds ?: minOf(DEFAULT_ROUNDS, playable.size)
            return FindCardGame(language, phrases, playable, rounds, random)
        }
    }
}

private fun com.omrihak.nfccards.content.Words.getValue(lang: String): WordForm =
    get(lang) ?: error("missing words for $lang")
