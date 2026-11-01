package com.omrihak.nfccards.game

import com.omrihak.nfccards.content.ContentException
import com.omrihak.nfccards.content.ContentMetadata
import com.omrihak.nfccards.content.ErrorKind
import com.omrihak.nfccards.content.WordForm
import com.omrihak.nfccards.content.Words
import com.omrihak.nfccards.game.GameAction.AwardStar
import com.omrihak.nfccards.game.GameAction.Finish
import com.omrihak.nfccards.game.GameAction.Say
import com.omrihak.nfccards.game.GameAction.ShowHint
import com.omrihak.nfccards.game.GameAction.WaitForCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class FindCardGameTest {
    private fun card(name: String, en: String, he: String, heAsk: String? = null) = GameCard(
        "https://a/$name.json",
        Words(mapOf("en" to WordForm(en), "he" to WordForm(he, heAsk))),
    )

    private val dog = card("dog", "dog", "כלב", "הכלב")
    private val cat = card("cat", "cat", "חתול", "החתול")
    private val cow = card("cow", "cow", "פרה")
    private val all = listOf(dog, cat, cow)

    private fun spec(lang: String = "en", rounds: Int? = null, say: String = "{}", deck: List<GameCard> = all): com.omrihak.nfccards.content.GameSpec {
        val links = deck.joinToString(",") { "\"${it.url}\"" }
        val roundsJson = if (rounds != null) ",\"rounds\":$rounds" else ""
        return ContentMetadata.parse(
            """{"type":"game","game":"find-card","lang":"$lang","deck":[$links],"say":$say$roundsJson}""",
        ).game!!
    }

    private fun game(lang: String = "en", rounds: Int? = null, say: String = "{}", deck: List<GameCard> = all, seed: Int = 1) =
        Games.create(spec(lang, rounds, say, deck), deck, Random(seed))

    private fun List<GameAction>.says() = filterIsInstance<Say>().map { it.text }

    /** The card the game is asking for, read from the question it just spoke. */
    private fun List<GameAction>.target(): GameCard {
        val question = says().last { it.startsWith("Where is the ") }
        return all.first { question == "Where is the ${it.words["en"]!!.text}?" }
    }

    @Test fun startSaysIntroThenAsksAndWaits() {
        val actions = game().start()
        assertEquals("Let's play!", (actions[0] as Say).text)
        assertEquals("en", (actions[0] as Say).lang)
        assertTrue((actions[1] as Say).text.startsWith("Where is the "))
        assertEquals(WaitForCard, actions[2])
        assertEquals(3, actions.size)
    }

    @Test fun rightCardGivesStarCheerAndNextQuestion() {
        val g = game()
        val target = g.start().target()
        val actions = g.onCard(target.url)
        assertEquals(AwardStar, actions[0])
        assertEquals("Yes! ${target.words["en"]!!.text}!", (actions[1] as Say).text)
        assertTrue(actions.says().last().startsWith("Where is the "))
        assertEquals(WaitForCard, actions.last())
    }

    @Test fun wrongCardNamesItAndAsksAgain() {
        val g = game()
        val target = g.start().target()
        val other = all.first { it != target }
        val actions = g.onCard(other.url)
        assertEquals("That's the ${other.words["en"]!!.text}. Try again!", (actions[0] as Say).text)
        assertEquals("Where is the ${target.words["en"]!!.text}?", actions[1].let { (it as Say).text })
        assertEquals(WaitForCard, actions[2])
        assertFalse(actions.any { it is ShowHint || it == AwardStar })
    }

    @Test fun unknownCardJustSaysTryAgain() {
        val g = game()
        g.start()
        val actions = g.onCard("https://elsewhere/card.json")
        assertEquals("Try again!", (actions[0] as Say).text)
    }

    @Test fun hintAppearsFromSecondMissAndStarClearsTheCount() {
        val g = game()
        val target = g.start().target()
        val other = all.first { it != target }
        assertFalse(g.onCard(other.url).any { it is ShowHint })
        assertTrue(g.onCard(other.url).contains(ShowHint(target.url)))
        // Next target starts with no misses.
        val next = g.onCard(target.url).target()
        val wrongForNext = all.first { it != next }
        assertFalse(g.onCard(wrongForNext.url).any { it is ShowHint })
    }

    @Test fun idleRepeatsTheQuestion() {
        val g = game()
        val target = g.start().target()
        val actions = g.onIdle()
        assertEquals(listOf(Say("Where is the ${target.words["en"]!!.text}?", "en"), WaitForCard), actions)
    }

    @Test fun finishesAfterLastRound() {
        val g = game(rounds = 2)
        var actions = g.start()
        actions = g.onCard(actions.target().url)
        assertFalse(actions.contains(Finish))
        actions = g.onCard(actions.target().url)
        assertEquals(AwardStar, actions[0])
        assertEquals("Great job!", actions.says().last())
        assertEquals(Finish, actions.last())
        assertFalse(actions.contains(WaitForCard))
        // Over: nothing more happens.
        assertTrue(g.onCard(dog.url).isEmpty())
        assertTrue(g.onIdle().isEmpty())
    }

    @Test fun noTargetRepeatsUntilAllWereAsked() {
        repeat(20) { seed ->
            val g = game(rounds = 3, seed = seed)
            var actions = g.start()
            val asked = mutableListOf<GameCard>()
            repeat(3) {
                val t = actions.target()
                asked += t
                actions = g.onCard(t.url)
            }
            assertEquals("seed $seed", 3, asked.toSet().size)
        }
    }

    @Test fun moreRoundsThanCardsReshufflesWithoutBackToBackRepeats() {
        repeat(20) { seed ->
            val g = game(rounds = 8, deck = listOf(dog, cat), seed = seed)
            var actions = g.start()
            var previous: GameCard? = null
            repeat(8) {
                val t = actions.target()
                assertTrue("seed $seed", t != previous)
                previous = t
                actions = g.onCard(t.url)
            }
            assertEquals(Finish, actions.last())
        }
    }

    @Test fun defaultRoundsIsFiveOrDeckSize() {
        val g = game() // 3 cards -> 3 rounds
        var actions = g.start()
        repeat(2) { actions = g.onCard(actions.target().url) }
        assertFalse(actions.contains(Finish))
        assertEquals(Finish, g.onCard(actions.target().url).last())
    }

    @Test fun hebrewUsesQuestionFormAndHebrewPhrases() {
        val g = game(lang = "he")
        val actions = g.start()
        assertEquals("בואו נשחק!", (actions[0] as Say).text)
        assertTrue(actions.all { it !is Say || it.lang == "he" })
        val question = (actions[1] as Say).text
        assertTrue(question, question in listOf("איפה הכלב?", "איפה החתול?", "איפה פרה?"))
    }

    @Test fun learningModeAsksInHebrewAndTeachesEnglish() {
        val g = game(lang = "he>en")
        val first = g.start()
        val question = first.says().last()
        val target = all.first { question == "איפה ${it.words["he"]!!.forQuestion}?" }
        val actions = g.onCard(target.url)
        val says = actions.filterIsInstance<Say>()
        assertEquals(Say("כן! ${target.words["he"]!!.text}!", "he"), says[0])
        assertEquals(Say(target.words["en"]!!.text, "en"), says[1])
    }

    @Test fun recordedAudioReplacesSpeech() {
        val recorded = GameCard(
            "https://a/bird.json",
            Words(mapOf("en" to WordForm("bird", audio = "https://a/bird.mp3"))),
        )
        val deck = listOf(recorded, GameCard("https://a/fish.json", Words(mapOf("en" to WordForm("fish")))))
        val g = game(deck = deck)
        var actions = g.start()
        // Two cards: if the fish is asked first, answering it makes the bird next.
        if (actions.says().last() == "Where is the fish?") actions = g.onCard("https://a/fish.json")
        val question = actions.filterIsInstance<Say>().last { it.text.startsWith("Where") }
        assertEquals("Where is the bird?", question.text)
        assertEquals("https://a/bird.mp3", question.audio)
    }

    @Test fun cardsWithoutTheLanguageAreLeftOut() {
        val onlyEnglish = GameCard("https://a/x.json", Words(mapOf("en" to WordForm("x"))))
        val g = game(lang = "he", deck = all + onlyEnglish)
        val seen = mutableSetOf<String>()
        var actions = g.start()
        repeat(3) {
            seen += actions.says().last()
            val t = all.first { actions.says().last() == "איפה ${it.words["he"]!!.forQuestion}?" }
            actions = g.onCard(t.url)
        }
        assertEquals(3, seen.size)
    }

    @Test fun phrasesCanBeOverridden() {
        val g = game(say = """{"intro":"Hi!","ask":"Find {word}!"}""")
        val actions = g.start()
        assertEquals("Hi!", (actions[0] as Say).text)
        assertTrue((actions[1] as Say).text.startsWith("Find "))
    }

    private fun kindOf(block: () -> Unit): ErrorKind {
        try {
            block()
        } catch (e: ContentException) {
            return e.kind
        }
        fail("expected ContentException")
        error("unreachable")
    }

    @Test fun needsTwoPlayableCards() {
        assertEquals(ErrorKind.UNSUPPORTED, kindOf { game(deck = listOf(dog)) })
        val onlyEnglish = GameCard("https://a/x.json", Words(mapOf("en" to WordForm("x"))))
        assertEquals(ErrorKind.UNSUPPORTED, kindOf { game(lang = "he", deck = listOf(dog, onlyEnglish)) })
    }

    @Test fun languageWithoutBuiltInPhrasesNeedsSay() {
        val fr = { n: String -> GameCard("https://a/$n.json", Words(mapOf("fr" to WordForm(n)))) }
        val deck = listOf(fr("chien"), fr("chat"))
        assertEquals(ErrorKind.UNSUPPORTED, kindOf { game(lang = "fr", deck = deck) })
        game(
            lang = "fr", deck = deck,
            say = """{"intro":"a","ask":"b {word}","correct":"c","wrong":"d","tryAgain":"e","finish":"f"}""",
        )
    }

    @Test fun unknownGame() {
        val s = ContentMetadata.parse("""{"type":"game","game":"memory","deck":["https://a/x"]}""").game!!
        assertEquals(ErrorKind.UNSUPPORTED, kindOf { Games.create(s, all) })
    }
}
