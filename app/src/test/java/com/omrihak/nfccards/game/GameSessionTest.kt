package com.omrihak.nfccards.game

import com.omrihak.nfccards.content.ContentMetadata
import com.omrihak.nfccards.content.WordForm
import com.omrihak.nfccards.content.Words
import com.omrihak.nfccards.speech.Speaker
import com.omrihak.nfccards.speech.SpeechUnavailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import kotlin.random.Random

class GameSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private class FakeSpeaker(val events: MutableList<String>, val speakMillis: Long = 0, val fail: Boolean = false) : Speaker {
        override suspend fun speak(text: String, lang: String) {
            if (fail) throw SpeechUnavailableException("no voice")
            events += "say:$lang:$text"
            try {
                delay(speakMillis)
            } catch (e: kotlinx.coroutines.CancellationException) {
                events += "cut:$text"
                throw e
            }
        }
    }

    private val listener = object : GameSession.Listener {
        override fun onHint(cardKey: String?) { events += "hint:$cardKey" }
        override fun onStar() { events += "star" }
        override fun onEnded() { events += "ended" }
        override fun onProblem(error: Exception) { events += "problem:${error.message}" }
    }

    private fun card(name: String, en: String) = GameCard("k:$name", Words(mapOf("en" to WordForm(en))))
    private val dog = card("dog", "dog")
    private val cat = card("cat", "cat")

    private fun newGame(rounds: Int = 2): Game {
        val spec = ContentMetadata.parse(
            """{"type":"game","game":"find-card","rounds":$rounds,"deck":["https://a/dog","https://a/cat"]}""",
        ).game!!
        return Games.create(spec, listOf(dog, cat), Random(3))
    }

    private fun session(
        speaker: Speaker = FakeSpeaker(events),
        clips: ClipPlayer = ClipPlayer { false },
        idleMillis: Long = 60_000,
        rounds: Int = 2,
    ) = GameSession(newGame(rounds), speaker, clips, scope, listener, idleMillis, maxIdleRepeats = 2)

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        withTimeout(3_000) { while (!condition()) delay(5) }
        assertTrue(what, condition())
    }

    private fun asked(): String = events.last { it.startsWith("say:en:Where") }.removePrefix("say:en:Where is the ").removeSuffix("?")

    @Test fun speaksIntroThenQuestionInOrder() = runBlocking {
        session().start()
        waitFor("question") { events.any { it.startsWith("say:en:Where") } }
        assertEquals("say:en:Let's play!", events[0])
    }

    @Test fun rightCardGivesStarBeforeTheCheer() = runBlocking {
        val s = session()
        s.start()
        waitFor("question") { events.any { it.startsWith("say:en:Where") } }
        events.clear()
        s.onCard("k:" + asked())
        waitFor("next question") { events.any { it.startsWith("say:en:Where") } }
        assertEquals("hint:null", events[0])
        assertEquals("star", events[1])
        assertTrue(events[2], events[2].startsWith("say:en:Yes!"))
    }

    @Test fun aTapCutsOffSpeechInProgress() = runBlocking {
        val s = session(speaker = FakeSpeaker(events, speakMillis = 10_000))
        s.start()
        waitFor("intro started") { events.contains("say:en:Let's play!") }
        s.onCard("k:dog")
        waitFor("cut") { events.contains("cut:Let's play!") }
        waitFor("answer spoken") { events.any { it.startsWith("say:en:That's the dog") || it.startsWith("say:en:Yes!") } }
    }

    @Test fun lastRoundEndsTheGame() = runBlocking {
        val s = session(rounds = 1)
        s.start()
        waitFor("question") { events.any { it.startsWith("say:en:Where") } }
        s.onCard("k:" + asked())
        waitFor("ended") { events.contains("ended") }
        assertTrue(events.indexOf("star") < events.indexOf("say:en:Great job!"))
        assertTrue(events.indexOf("say:en:Great job!") < events.indexOf("ended"))
    }

    @Test fun idleRepeatsTheQuestionThenEnds() = runBlocking {
        val s = session(idleMillis = 30)
        s.start()
        waitFor("ended") { events.contains("ended") }
        // Asked once at the start, repeated twice, then the child is considered gone.
        assertEquals(3, events.count { it.startsWith("say:en:Where") })
    }

    @Test fun recordedClipReplacesSpeechWhenAvailable() = runBlocking {
        val recorded = GameCard("k:bird", Words(mapOf("en" to WordForm("bird", audio = "https://a/bird.mp3"))))
        val fish = GameCard("k:fish", Words(mapOf("en" to WordForm("fish"))))
        val spec = ContentMetadata.parse("""{"type":"game","game":"find-card","deck":["https://a/b","https://a/f"]}""").game!!
        val played = Collections.synchronizedList(mutableListOf<String>())
        val s = GameSession(
            Games.create(spec, listOf(recorded, fish), Random(1)),
            FakeSpeaker(events), ClipPlayer { played += it; true }, scope, listener, 60_000,
        )
        s.start()
        waitFor("question") { events.any { it.startsWith("say:en:Where") } || played.isNotEmpty() }
        s.cancel()
        // If the bird was asked, its recording played and its text was not spoken.
        if (played.isNotEmpty()) {
            assertEquals(listOf("https://a/bird.mp3"), played.distinct())
            assertTrue(events.none { it == "say:en:Where is the bird?" })
        }
    }

    @Test fun missingVoiceReportsAProblem() = runBlocking {
        val s = session(speaker = FakeSpeaker(events, fail = true))
        s.start()
        waitFor("problem") { events.any { it.startsWith("problem:") } }
        assertEquals("problem:no voice", events.last())
    }

    @Test fun cancelStopsEverything() = runBlocking {
        val s = session(speaker = FakeSpeaker(events, speakMillis = 10_000))
        s.start()
        waitFor("speaking") { events.contains("say:en:Let's play!") }
        s.cancel()
        waitFor("cut") { events.contains("cut:Let's play!") }
        delay(50)
        assertTrue(events.none { it == "ended" || it.startsWith("problem") })
    }

    @Test fun cardKeyComparesDriveLinksById() {
        val a = CardKey.of("https://drive.google.com/file/d/1AbCdEfGhIjKlMn/view?usp=sharing")
        val b = CardKey.of("  https://drive.google.com/file/d/1AbCdEfGhIjKlMn/view ")
        assertEquals(a, b)
        assertEquals("https://example.com/x.json", CardKey.of(" https://example.com/x.json "))
    }
}
