package com.omrihak.nfccards.remote

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A deliberately tiny HTTP/1.1 server for the parent's companion app: one request per connection,
 * small bodies, no keep-alive. The routes are in [RemoteApi].
 *
 * It listens on every interface, but only answers clients on the local network (private, link-local
 * or loopback addresses), so the player is controllable from the home Wi-Fi and nowhere else.
 */
class RemoteServer(
    private val port: Int,
    private val scope: CoroutineScope,
    private val handle: suspend (HttpRequest) -> HttpResponse,
) {
    private var job: Job? = null
    @Volatile private var socket: ServerSocket? = null

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            val server = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            } catch (e: IOException) {
                Log.e(TAG, "Remote control unavailable: cannot listen on port $port", e)
                return@launch
            }
            socket = server
            Log.i(TAG, "Remote control listening on port $port")
            server.use {
                while (isActive) {
                    // accept() can't be cancelled; stop() closes the socket, which ends it with an exception.
                    val client = try {
                        server.accept()
                    } catch (e: IOException) {
                        break
                    }
                    launch { serve(client) }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try {
            socket?.close()
        } catch (e: IOException) {
            // Closing anyway.
        }
        socket = null
    }

    private suspend fun serve(client: Socket) {
        client.use {
            try {
                client.soTimeout = READ_TIMEOUT_MILLIS
                val response = if (!isLocal(client.inetAddress)) {
                    Log.w(TAG, "Refused remote control from ${client.inetAddress}")
                    HttpResponse.error(403, "Only the local network can control the player")
                } else {
                    val request = readRequest(client.getInputStream())
                    if (request == null) {
                        HttpResponse.error(400, "Bad request")
                    } else {
                        try {
                            handle(request)
                        } catch (e: Exception) {
                            Log.e(TAG, "${request.method} ${request.path} failed", e)
                            HttpResponse.error(500, e.message ?: e.toString())
                        }
                    }
                }
                writeResponse(client.getOutputStream(), response)
            } catch (e: IOException) {
                Log.d(TAG, "Remote client dropped: ${e.message}")
            }
        }
    }

    private fun isLocal(address: InetAddress?): Boolean =
        address != null && (address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress)

    companion object {
        private const val TAG = "RemoteServer"
        private const val READ_TIMEOUT_MILLIS = 5_000
        private const val MAX_HEADER_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = 64 * 1024

        /** Reads one request. Null if it is malformed or too large. */
        fun readRequest(input: InputStream): HttpRequest? {
            val stream = BufferedInputStream(input)
            val head = readHead(stream) ?: return null
            val lines = head.split("\r\n")
            val parts = lines.first().split(" ")
            if (parts.size < 2) return null
            val headers = lines.drop(1)
                .mapNotNull { line ->
                    val colon = line.indexOf(':')
                    if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
                }
                .toMap()
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > MAX_BODY_BYTES) return null
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = stream.read(body, read, length - read)
                if (n < 0) return null
                read += n
            }
            val path = parts[1].substringBefore('?')
            return HttpRequest(parts[0].uppercase(), path, String(body, Charsets.UTF_8))
        }

        /** Everything up to the blank line after the headers, without it. */
        private fun readHead(stream: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            var matched = 0 // how much of \r\n\r\n has been seen
            while (bytes.size() < MAX_HEADER_BYTES) {
                val b = stream.read()
                if (b < 0) return null
                bytes.write(b)
                matched = when {
                    b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                    b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                    b == '\r'.code -> 1
                    else -> 0
                }
                if (matched == 4) return bytes.toString(Charsets.ISO_8859_1.name()).dropLast(4)
            }
            return null
        }

        fun writeResponse(output: OutputStream, response: HttpResponse) {
            val body = response.body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 ${response.status} ${reason(response.status)}\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            output.write(body)
            output.flush()
        }

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
    }
}

data class HttpRequest(val method: String, val path: String, val body: String)

data class HttpResponse(val status: Int, val body: String) {
    companion object {
        fun error(status: Int, message: String): HttpResponse {
            val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
            return HttpResponse(status, """{"error":"$escaped"}""")
        }
    }
}
