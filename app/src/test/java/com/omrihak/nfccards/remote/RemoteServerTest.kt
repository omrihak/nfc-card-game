package com.omrihak.nfccards.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/** The server over a real socket, the way the companion app calls it. `/api/` goes to a real [RemoteApi]. */
class RemoteServerTest {

    private val token = "t".repeat(43)
    private val played = mutableListOf<String>()

    private val api = RemoteApi(
        object : RemoteApi.Player {
            override fun status() = JSONObject().put("state", "idle").put("paused", false)
            override fun play(cardUrl: String) { synchronized(played) { played += cardUrl } }
            override fun pause() = Unit
            override fun resume() = Unit
            override fun stop() = Unit
        },
        object : RemoteApi.Volume {
            override val level = 3
            override val max = 15
            override fun set(level: Int) = Unit
        },
        object : RemoteApi.Admin {
            override val version = "1.0"
            override val isDeviceOwner = false
            override val updateState = "idle"
            override val updateMessage: String? = null
            override fun openSettings() = Unit
            override fun release() = Unit
            override fun update() = false
        },
    ) { listOf(token) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val port = ServerSocket(0).use { it.localPort }
    private val server = RemoteServer(port, scope) { request ->
        when (request.path) {
            "/boom" -> throw IllegalStateException("boom")
            "/echo" -> HttpResponse(200, JSONObject().put("body", request.body).toString())
            else -> api.handle(request)
        }
    }.also { it.start() }

    @After fun tearDown() {
        server.stop()
        scope.cancel()
    }

    private fun call(method: String, path: String, body: String? = null, auth: String? = token): Pair<Int, String> {
        repeat(50) { attempt ->
            try {
                val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
                c.requestMethod = method
                if (auth != null) c.setRequestProperty("Authorization", "Bearer $auth")
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
                return code to text
            } catch (e: IOException) {
                if (attempt == 49) throw e
                Thread.sleep(20) // the server is still binding
            }
        }
        error("unreachable")
    }

    @Test fun servesRequests() {
        val (code, text) = call("GET", "/api/status")
        assertEquals(200, code)
        assertEquals("tapandplay", JSONObject(text).getString("app"))
        assertEquals(200, call("POST", "/api/play", """{"url":"https://x"}""").first)
        assertEquals(listOf("https://x"), synchronized(played) { played.toList() })
        assertEquals("""{"body":"hi"}""", call("POST", "/echo", "hi").second)
    }

    @Test fun refusesUnpairedClients() {
        for (auth in listOf(null, "nope")) {
            val (code, text) = call("POST", "/api/play", """{"url":"https://x"}""", auth = auth)
            assertEquals(401, code)
            val json = JSONObject(text)
            assertEquals("tapandplay", json.getString("app"))
            assertFalse(json.getBoolean("paired"))
        }
        assertTrue(synchronized(played) { played.isEmpty() })
    }

    @Test fun handlerErrorsBecome500() {
        val (code, text) = call("GET", "/boom")
        assertEquals(500, code)
        assertTrue(text.contains("boom"))
    }
}
