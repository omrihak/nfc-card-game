package com.omrihak.nfccards.companion.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What the player reports (its `GET /api/status`). */
data class PlayerStatus(
    /** "idle" | "loading" | "audio" | "video" */
    val state: String,
    val paused: Boolean,
    val title: String?,
    val card: String?,
    val positionMs: Long?,
    val durationMs: Long?,
    val volume: Int,
    val maxVolume: Int,
) {
    val isPlayingSomething get() = state == "audio" || state == "video"

    companion object {
        /** Throws if [text] isn't a Tap and Play player's status. */
        fun parse(text: String): PlayerStatus {
            val json = JSONObject(text)
            if (json.optString("app") != APP_ID) throw IOException("Not a Tap and Play player")
            return PlayerStatus(
                state = json.optString("state", "idle"),
                paused = json.optBoolean("paused"),
                title = json.optStringOrNull("title"),
                card = json.optStringOrNull("card"),
                positionMs = if (json.has("positionMs")) json.optLong("positionMs") else null,
                durationMs = if (json.has("durationMs")) json.optLong("durationMs") else null,
                volume = json.optInt("volume"),
                maxVolume = json.optInt("maxVolume", 15).coerceAtLeast(1),
            )
        }

        private const val APP_ID = "tapandplay"

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (!has(key) || isNull(key)) null else optString(key).ifEmpty { null }
    }
}

/**
 * Talks to the player app's remote control (see the player's RemoteApi) at [address], "host:port".
 * Works only while both phones are on the same Wi-Fi.
 */
class PlayerClient(val address: String) {

    suspend fun status(): PlayerStatus = call("GET", "/api/status")
    suspend fun play(cardUrl: String) = call("POST", "/api/play", JSONObject().put("url", cardUrl))
    suspend fun pause() = call("POST", "/api/pause")
    suspend fun resume() = call("POST", "/api/resume")
    suspend fun stop() = call("POST", "/api/stop")
    suspend fun setVolume(level: Int) = call("POST", "/api/volume", JSONObject().put("level", level))
    suspend fun stepVolume(step: Int) = call("POST", "/api/volume", JSONObject().put("step", step))

    private suspend fun call(method: String, path: String, body: JSONObject? = null): PlayerStatus =
        withContext(Dispatchers.IO) {
            val connection = URL("http://$address$path").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.useCaches = false
                if (body != null || method == "POST") {
                    val bytes = (body ?: JSONObject()).toString().toByteArray()
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    throw IOException(message?.ifEmpty { null } ?: "Player answered $code")
                }
                PlayerStatus.parse(text)
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 2_500
        const val READ_TIMEOUT_MILLIS = 5_000
    }
}
