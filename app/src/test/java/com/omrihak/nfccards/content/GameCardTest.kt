package com.omrihak.nfccards.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GameCardTest {
    private fun kindOf(json: String): ErrorKind {
        try {
            ContentMetadata.parse(json)
        } catch (e: ContentException) {
            return e.kind
        }
        fail("expected ContentException")
        error("unreachable")
    }

    private val deck = """["https://a/dog.json","https://a/cat.json"]"""

    @Test fun minimalGameCard() {
        val m = ContentMetadata.parse("""{"type":"game","game":"find-card","deck":$deck}""")
        assertEquals("game", m.type)
        val g = m.game!!
        assertEquals("find-card", g.game)
        assertEquals(GameLanguage("en", "en"), g.lang)
        assertEquals(listOf("https://a/dog.json", "https://a/cat.json"), g.deck)
        assertNull(g.rounds)
        assertTrue(g.say.isEmpty())
    }

    @Test fun fullGameCard() {
        val m = ContentMetadata.parse(
            """{"type":"GAME","game":"Find-Card","name":"Animals","lang":"he>en","rounds":3,"deck":$deck,
               "say":{"ask":"איפה {word}?","finish":null}}""",
        )
        val g = m.game!!
        assertEquals("find-card", g.game)
        assertEquals("Animals", m.name)
        assertEquals(GameLanguage("he", "en"), g.lang)
        assertEquals(3, g.rounds)
        assertEquals(mapOf("ask" to "איפה {word}?"), g.say)
    }

    @Test fun duplicateDeckEntriesAreMerged() {
        val m = ContentMetadata.parse("""{"type":"game","game":"find-card","deck":["https://a/x","https://a/x"]}""")
        assertEquals(1, m.game!!.deck.size)
    }

    @Test fun gameNeedsGameField() = assertEquals(ErrorKind.MALFORMED_METADATA, kindOf("""{"type":"game","deck":$deck}"""))
    @Test fun gameNeedsDeck() = assertEquals(ErrorKind.MALFORMED_METADATA, kindOf("""{"type":"game","game":"x"}"""))
    @Test fun emptyDeck() = assertEquals(ErrorKind.MALFORMED_METADATA, kindOf("""{"type":"game","game":"x","deck":[]}"""))
    @Test fun badDeckUrl() = assertEquals(ErrorKind.INVALID_URL, kindOf("""{"type":"game","game":"x","deck":["ftp://a"]}"""))
    @Test fun badLanguage() = assertEquals(ErrorKind.MALFORMED_METADATA, kindOf("""{"type":"game","game":"x","lang":"a>","deck":$deck}"""))
    @Test fun zeroRounds() = assertEquals(ErrorKind.MALFORMED_METADATA, kindOf("""{"type":"game","game":"x","rounds":0,"deck":$deck}"""))

    @Test fun audioCardWithWords() {
        val m = ContentMetadata.parse(
            """{"type":"audio","media":"https://a/b.mp3","words":{
               "en":{"text":"dog"},
               "HE":{"text":"כלב","ask":"הכלב","audio":"https://a/dog_he.mp3"},
               "fr":{"nope":1},
               "de":"Hund"}}""",
        )
        val words = m.words
        assertEquals(setOf("en", "he"), words.languages)
        assertEquals("dog", words["en"]!!.text)
        assertEquals("dog", words["en"]!!.forQuestion)
        assertEquals("הכלב", words["he"]!!.forQuestion)
        assertEquals("https://a/dog_he.mp3", words["he"]!!.audio)
        assertNull(words["fr"])
    }

    @Test fun cardWithoutWords() = assertTrue(
        ContentMetadata.parse("""{"type":"audio","media":"https://a/b.mp3"}""").words.isEmpty,
    )

    @Test fun nonHttpWordAudioIsIgnored() {
        val m = ContentMetadata.parse("""{"type":"audio","media":"https://a/b.mp3","words":{"en":{"text":"dog","audio":"file:///x"}}}""")
        assertNull(m.words["en"]!!.audio)
    }

    @Test fun wordCardNeedsNoMedia() {
        val m = ContentMetadata.parse(
            """{"type":"word","name":"Dog","image":"https://a/dog.jpg","words":{"he":{"text":"כלב"},"fr":{"text":"chien"},"en":{"text":"dog"}}}""",
        )
        assertEquals("word", m.type)
        assertEquals("", m.media)
        assertEquals("https://a/dog.jpg", m.image)
        // English first, then the others alphabetically.
        assertEquals(listOf("en", "fr", "he"), m.words.inSpeakingOrder.map { it.first })
    }

    @Test fun wordCardWithoutWordsIsMalformed() {
        assertEquals(ErrorKind.MALFORMED_METADATA, kindOf("""{"type":"word"}"""))
        assertEquals(ErrorKind.MALFORMED_METADATA, kindOf("""{"type":"word","words":{"en":{"nope":1}}}"""))
    }

    @Test fun wordCardWithBadImage() = assertEquals(ErrorKind.INVALID_URL, kindOf("""{"type":"word","image":"x","words":{"en":{"text":"a"}}}"""))
}
