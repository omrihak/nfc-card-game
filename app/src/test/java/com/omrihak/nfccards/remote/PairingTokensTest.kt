package com.omrihak.nfccards.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTokensTest {

    private val token = "Abc_-0123456789abcdefghijklmnopqrstuvwxyz"

    @Test fun parsesPairingUris() {
        assertEquals(token, PairingTokens.tokenFrom("tapandplay://pair?token=$token"))
        assertEquals(token, PairingTokens.tokenFrom(" TAPANDPLAY://PAIR?v=1&token=$token "))
        assertEquals(token, PairingTokens.tokenFrom("tapandplay://pair/?token=$token&x=y"))
    }

    @Test fun rejectsEverythingElse() {
        assertNull(PairingTokens.tokenFrom("tapandplay://settings"))
        assertNull(PairingTokens.tokenFrom("tapandplay://pair"))
        assertNull(PairingTokens.tokenFrom("tapandplay://pair?token="))
        assertNull(PairingTokens.tokenFrom("tapandplay://pair?token=short"))
        assertNull(PairingTokens.tokenFrom("tapandplay://pair?token=${"x".repeat(31)}"))
        assertNull(PairingTokens.tokenFrom("tapandplay://pair?token=${"x".repeat(31)}%2F"))
        assertNull(PairingTokens.tokenFrom("tapandplay://pair?xtoken=$token"))
        assertNull(PairingTokens.tokenFrom("https://pair?token=$token"))
        assertNull(PairingTokens.tokenFrom("https://drive.google.com/file/d/x/view"))
        assertNull(PairingTokens.tokenFrom("not a uri at all"))
    }

    @Test fun keepsTheNewestFour() {
        var tokens = emptyList<String>()
        for (i in 1..5) tokens = PairingTokens.add(tokens, "t$i")
        assertEquals(listOf("t5", "t4", "t3", "t2"), tokens)
        // Pairing again moves a token to the front instead of duplicating it.
        assertEquals(listOf("t3", "t5", "t4", "t2"), PairingTokens.add(tokens, "t3"))
    }

    @Test fun encodeDecodeRoundTrip() {
        val tokens = listOf(token, "b".repeat(32))
        assertEquals(tokens, PairingTokens.decode(PairingTokens.encode(tokens)))
        assertTrue(PairingTokens.decode(null).isEmpty())
        assertTrue(PairingTokens.decode("").isEmpty())
    }

    @Test fun bearerHeader() {
        assertEquals("abc", PairingTokens.bearer("Bearer abc"))
        assertEquals("abc", PairingTokens.bearer("bearer  abc "))
        assertNull(PairingTokens.bearer("Bearer "))
        assertNull(PairingTokens.bearer("Basic abc"))
        assertNull(PairingTokens.bearer(null))
    }

    @Test fun matches() {
        val tokens = listOf("b".repeat(32), token)
        assertTrue(PairingTokens.matches(tokens, token))
        assertFalse(PairingTokens.matches(tokens, token.dropLast(1)))
        assertFalse(PairingTokens.matches(tokens, null))
        assertFalse(PairingTokens.matches(emptyList(), token))
    }
}
