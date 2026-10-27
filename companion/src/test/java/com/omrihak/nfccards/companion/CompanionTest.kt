package com.omrihak.nfccards.companion

import com.omrihak.nfccards.companion.cards.Card
import com.omrihak.nfccards.companion.cards.CardStore
import com.omrihak.nfccards.companion.cards.Drive
import com.omrihak.nfccards.companion.player.AdminStickers
import com.omrihak.nfccards.companion.player.NotPairedException
import com.omrihak.nfccards.companion.player.Pairing
import com.omrihak.nfccards.companion.player.PlayerError
import com.omrihak.nfccards.companion.player.PlayerStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CompanionTest {

    @Test fun parsesPlayingStatus() {
        val s = PlayerStatus.parse(
            """{"app":"tapandplay","state":"audio","paused":true,"title":"Baby Shark","card":"https://c",
               "positionMs":1500,"durationMs":90000,"volume":7,"maxVolume":15}""",
        )
        assertEquals("audio", s.state)
        assertTrue(s.paused)
        assertTrue(s.isPlayingSomething)
        assertEquals("Baby Shark", s.title)
        assertEquals(1500L, s.positionMs)
        assertEquals(90000L, s.durationMs)
        assertEquals(7, s.volume)
    }

    @Test fun parsesIdleStatus() {
        val s = PlayerStatus.parse("""{"app":"tapandplay","state":"idle","paused":false,"volume":3,"maxVolume":15}""")
        assertFalse(s.isPlayingSomething)
        assertNull(s.title)
        assertNull(s.positionMs)
    }

    @Test fun parsesAdminFieldsAndOlderPlayers() {
        val s = PlayerStatus.parse(
            """{"app":"tapandplay","state":"idle","volume":3,"maxVolume":15,"version":"0.2.0","deviceOwner":true,
               "update":"failed","updateMessage":"Install failed: x"}""",
        )
        assertEquals("0.2.0", s.version)
        assertTrue(s.deviceOwner)
        assertEquals("failed", s.update)
        assertFalse(s.isUpdating)
        assertEquals("Install failed: x", s.updateMessage)
        val old = PlayerStatus.parse("""{"app":"tapandplay","state":"idle"}""")
        assertNull(old.version)
        assertFalse(old.deviceOwner)
        assertEquals("idle", old.update)
    }

    @Test fun recognisesAnUnpairedPlayer() {
        assertTrue(PlayerStatus.failure(401, """{"app":"tapandplay","paired":false,"error":"Not paired"}""") is NotPairedException)
        // Something else answering 401 is not a player waiting to pair.
        assertTrue(PlayerStatus.failure(401, """{"error":"nope"}""") is PlayerError)
        assertTrue(PlayerStatus.failure(401, "<html>") is PlayerError)
        val conflict = PlayerStatus.failure(409, """{"error":"Only in kiosk mode"}""")
        assertTrue(conflict is PlayerError)
        assertEquals("Only in kiosk mode", conflict.message)
        assertEquals("Player answered 500", PlayerStatus.failure(500, "").message)
    }

    @Test fun pairingTokensAreLongRandomAndUrlSafe() {
        val tokens = (1..50).map { Pairing.newToken() }
        tokens.forEach { assertTrue(it, Regex("[A-Za-z0-9_-]{43}").matches(it)) }
        assertEquals(tokens.size, tokens.toSet().size)
    }

    @Test fun pairingUriAndQr() {
        val token = Pairing.newToken()
        assertEquals("tapandplay://pair?token=$token", Pairing.uri(token))
        val qr = Pairing.qr(Pairing.uri(token))
        assertTrue(qr.width >= 25 && qr.width == qr.height)
    }

    @Test fun adminStickersUseThePlayersUris() {
        assertEquals("tapandplay://settings", AdminStickers.settings.contentUrl)
        assertEquals("tapandplay://release", AdminStickers.release.contentUrl)
        assertEquals(Pairing.uri("t"), AdminStickers.pairing("t").contentUrl)
        assertEquals(AdminStickers.TYPE, AdminStickers.release.type)
    }

    @Test(expected = IOException::class)
    fun rejectsOtherServers() {
        PlayerStatus.parse("""{"state":"idle"}""")
    }

    @Test fun indexRoundTripKeepsUnknownFields() {
        val text = """{"cards":[{"name":"A","type":"video","contentUrl":"https://x","folderId":"f","printedAt":"y"}]}"""
        val cards = CardStore.parseIndex(text)
        assertEquals(1, cards.size)
        assertEquals("video", cards[0].type)
        assertEquals("f", cards[0].folderId)
        val again = JSONObject(CardStore.indexJson(cards)).getJSONArray("cards").getJSONObject(0)
        assertEquals("y", again.getString("printedAt"))
    }

    @Test fun unreadableIndexIsEmpty() {
        assertTrue(CardStore.parseIndex("not json").isEmpty())
        assertTrue(CardStore.parseIndex("{}").isEmpty())
        assertTrue(CardStore.indexJson(emptyList<Card>()).contains("\"cards\""))
    }

    @Test fun contentJsonIsThePlayersFormat() {
        val json = JSONObject(CardStore.contentJson("audio", "https://m", "https://i", "Song", "2026-01-01T00:00:00Z"))
        assertEquals("audio", json.getString("type"))
        assertEquals("https://m", json.getString("media"))
        assertEquals("https://i", json.getString("image"))
        assertEquals("Song", json.getString("name"))
        assertFalse(JSONObject(CardStore.contentJson("video", "https://m", null, "V", "t")).has("image"))
    }

    @Test fun driveLinks() {
        assertEquals("abc-1_2", Drive.idFromLink("https://drive.google.com/file/d/abc-1_2/view?usp=sharing"))
        assertEquals("xyz", Drive.idFromLink("https://drive.usercontent.google.com/download?id=xyz&export=download"))
        assertNull(Drive.idFromLink("https://example.com/"))
        assertEquals("https://drive.google.com/file/d/q/view?usp=sharing", Drive.shareLink("q"))
        assertEquals("'it\\'s'", Drive.quote("it's"))
    }
}
