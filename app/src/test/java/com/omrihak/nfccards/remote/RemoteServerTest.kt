package com.omrihak.nfccards.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/** The server over a real socket, the way the companion app calls it. */
class RemoteServerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val port = ServerSocket(0).use { it.localPort }
    private val requests = mutableListOf<HttpRequest>()
    private val server = RemoteServer(port, scope) { request ->
        synchronized(requests) { requests += request }
        if (request.path == "/boom") throw IllegalStateException("boom")
        HttpResponse(200, """{"ok":true}""")
    }.also { it.start() }

    @After fun tearDown() {
        server.stop()
        scope.cancel()
    }

    private fun call(method: String, path: String, body: String? = null): Pair<Int, String> {
        repeat(50) { attempt ->
            try {
                val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
                c.requestMethod = method
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
        assertEquals(200 to """{"ok":true}""", call("GET", "/api/status"))
        assertEquals(200, call("POST", "/api/play", """{"url":"https://x"}""").first)
        val last = synchronized(requests) { requests.last() }
        assertEquals("POST", last.method)
        assertEquals("""{"url":"https://x"}""", last.body)
    }

    @Test fun handlerErrorsBecome500() {
        val (code, text) = call("GET", "/boom")
        assertEquals(500, code)
        assertTrue(text.contains("boom"))
    }
}
