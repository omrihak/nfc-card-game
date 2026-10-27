package com.omrihak.nfccards.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class RemoteApiTest {

    private class FakePlayer : RemoteApi.Player {
        val calls = mutableListOf<String>()
        override fun status() = JSONObject().put("state", "idle").put("paused", false)
        override fun play(cardUrl: String) { calls += "play $cardUrl" }
        override fun pause() { calls += "pause" }
        override fun resume() { calls += "resume" }
        override fun stop() { calls += "stop" }
    }

    private class FakeVolume : RemoteApi.Volume {
        override var level = 5
        override val max = 15
        override fun set(level: Int) { this.level = level }
    }

    private val player = FakePlayer()
    private val volume = FakeVolume()
    private val api = RemoteApi(player, volume)

    private fun call(method: String, path: String, body: String = "") = api.handle(HttpRequest(method, path, body))

    @Test fun statusIncludesVolume() {
        val response = call("GET", "/api/status")
        assertEquals(200, response.status)
        val json = JSONObject(response.body)
        assertEquals("tapandplay", json.getString("app"))
        assertEquals("idle", json.getString("state"))
        assertEquals(5, json.getInt("volume"))
        assertEquals(15, json.getInt("maxVolume"))
    }

    @Test fun playPassesTheCardUrl() {
        assertEquals(200, call("POST", "/api/play", """{"url":" https://drive.google.com/file/d/x/view "}""").status)
        assertEquals(listOf("play https://drive.google.com/file/d/x/view"), player.calls)
    }

    @Test fun playRejectsNonHttpUrls() {
        assertEquals(400, call("POST", "/api/play", """{"url":"tapandplay://release"}""").status)
        assertEquals(400, call("POST", "/api/play", "").status)
        assertTrue(player.calls.isEmpty())
    }

    @Test fun simpleCommands() {
        call("POST", "/api/pause")
        call("POST", "/api/resume")
        call("POST", "/api/stop/")
        assertEquals(listOf("pause", "resume", "stop"), player.calls)
    }

    @Test fun volumeLevelAndStepAreClamped() {
        call("POST", "/api/volume", """{"step":1}""")
        assertEquals(6, volume.level)
        call("POST", "/api/volume", """{"level":99}""")
        assertEquals(15, volume.level)
        call("POST", "/api/volume", """{"step":-1}""")
        assertEquals(14, volume.level)
        call("POST", "/api/volume", """{"level":-3}""")
        assertEquals(0, volume.level)
        assertEquals(400, call("POST", "/api/volume", "{}").status)
    }

    @Test fun wrongMethodOrPath() {
        assertEquals(405, call("GET", "/api/stop").status)
        assertEquals(404, call("GET", "/nope").status)
        assertEquals(400, call("POST", "/api/volume", "not json").status)
    }

    @Test fun readsRequestWithBody() {
        val raw = "POST /api/play?x=1 HTTP/1.1\r\nHost: a\r\nContent-Length: 13\r\n\r\n{\"url\":\"h\"}  "
        val request = RemoteServer.readRequest(raw.byteInputStream())!!
        assertEquals("POST", request.method)
        assertEquals("/api/play", request.path)
        assertEquals("{\"url\":\"h\"}  ", request.body)
    }

    @Test fun readsRequestWithoutBody() {
        val request = RemoteServer.readRequest("GET /api/status HTTP/1.1\r\n\r\n".byteInputStream())!!
        assertEquals("GET", request.method)
        assertEquals("", request.body)
    }

    @Test fun rejectsTruncatedRequests() {
        assertNull(RemoteServer.readRequest("GET /api/status HTTP/1.1\r\n".byteInputStream()))
        assertNull(RemoteServer.readRequest("POST /a HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort".byteInputStream()))
    }

    @Test fun writesResponse() {
        val out = ByteArrayOutputStream()
        RemoteServer.writeResponse(out, HttpResponse(200, "{\"é\":1}"))
        val text = out.toString(Charsets.UTF_8.name())
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: 8\r\n"))
        assertTrue(text.endsWith("\r\n\r\n{\"é\":1}"))
    }
}
