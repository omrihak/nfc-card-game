package com.omrihak.nfccards.remote

import org.json.JSONException
import org.json.JSONObject

/**
 * The companion app's API. All calls run on the main thread.
 *
 *   GET  /api/status                   what is playing (see [Player.status]) plus the volume
 *   POST /api/play    {"url": "..."}   plays a card's content.json link, exactly as if the card was tapped
 *   POST /api/pause                    pauses
 *   POST /api/resume                   resumes after a pause
 *   POST /api/stop                     stops and goes back to the idle screen
 *   POST /api/volume  {"level": 7}     sets the media volume (0..maxVolume)
 *                     {"step": 1}      or moves it up (1) or down (-1)
 *
 * Every successful call answers with the status, so the companion's screen updates at once.
 */
class RemoteApi(private val player: Player, private val volume: Volume) {

    interface Player {
        /** `state` ("idle" | "loading" | "audio" | "video"), `paused`, and when there is one, `title`, `card`, `positionMs`, `durationMs`. */
        fun status(): JSONObject
        fun play(cardUrl: String)
        fun pause()
        fun resume()
        fun stop()
    }

    interface Volume {
        val level: Int
        val max: Int
        fun set(level: Int)
    }

    fun handle(request: HttpRequest): HttpResponse {
        val route = request.path.trimEnd('/')
        val method = ROUTES[route] ?: return HttpResponse.error(404, "No such endpoint: ${request.path}")
        if (request.method != method) return HttpResponse.error(405, "Use $method for $route")

        val body = try {
            if (request.body.isBlank()) JSONObject() else JSONObject(request.body)
        } catch (e: JSONException) {
            return HttpResponse.error(400, "Body is not a JSON object")
        }

        when (route) {
            "/api/status" -> Unit
            "/api/play" -> {
                val url = body.optString("url").trim()
                if (!url.startsWith("https://") && !url.startsWith("http://")) {
                    return HttpResponse.error(400, "\"url\" must be a card's http(s) link")
                }
                player.play(url)
            }
            "/api/pause" -> player.pause()
            "/api/resume" -> player.resume()
            "/api/stop" -> player.stop()
            "/api/volume" -> {
                val level = when {
                    body.has("level") -> body.optInt("level", volume.level)
                    body.has("step") -> volume.level + body.optInt("step", 0)
                    else -> return HttpResponse.error(400, "Give \"level\" or \"step\"")
                }
                volume.set(level.coerceIn(0, volume.max))
            }
        }
        return HttpResponse(200, status().toString())
    }

    private fun status(): JSONObject = player.status().apply {
        put("app", APP_ID)
        put("volume", volume.level)
        put("maxVolume", volume.max)
    }

    companion object {
        /** Lets the companion tell a Tap and Play player from anything else answering on the port. */
        const val APP_ID = "tapandplay"

        private val ROUTES = mapOf(
            "/api/status" to "GET",
            "/api/play" to "POST",
            "/api/pause" to "POST",
            "/api/resume" to "POST",
            "/api/stop" to "POST",
            "/api/volume" to "POST",
        )
    }
}
