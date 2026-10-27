package com.omrihak.nfccards.remote

import org.json.JSONException
import org.json.JSONObject

/**
 * The companion app's API. All calls run on the main thread. Every `/api/` call needs
 * `Authorization: Bearer <token>` with a token the player was paired with (see [Pairing]); without
 * one it answers 401 with `{"app":"tapandplay","paired":false}`, so the companion still knows it
 * found a player and can offer to pair.
 *
 *   GET  /api/status                   what is playing (see [Player.status]) plus the volume
 *   POST /api/play    {"url": "..."}   plays a card's content.json link, exactly as if the card was tapped
 *   POST /api/pause                    pauses
 *   POST /api/resume                   resumes after a pause
 *   POST /api/stop                     stops and goes back to the idle screen
 *   POST /api/volume  {"level": 7}     sets the media volume (0..maxVolume)
 *                     {"step": 1}      or moves it up (1) or down (-1)
 *   POST /api/admin/settings           like the settings admin card: leaves kiosk, opens Settings
 *   POST /api/admin/release            like the release admin card: gives up device owner for good
 *   POST /api/admin/update             downloads and installs the latest player in the background
 *                                      (device owner only, else 409); progress is in the status
 *
 * Every successful call answers with the status, so the companion's screen updates at once.
 */
class RemoteApi(
    private val player: Player,
    private val volume: Volume,
    private val admin: Admin,
    /** The paired tokens; read on every request, so a new pairing works at once. */
    private val tokens: () -> List<String>,
) {

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

    interface Admin {
        val version: String
        val isDeviceOwner: Boolean
        /** "idle" | "downloading" | "installing" | "failed" */
        val updateState: String
        /** Why the last update failed, or that there was nothing newer. */
        val updateMessage: String?
        fun openSettings()
        fun release()
        /** Starts updating in the background. False if this player can't install silently. */
        fun update(): Boolean
    }

    fun handle(request: HttpRequest): HttpResponse {
        val route = request.path.trimEnd('/')
        if (route.startsWith("/api/") &&
            !PairingTokens.matches(tokens(), PairingTokens.bearer(request.headers["authorization"]))
        ) {
            return notPaired()
        }
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
            "/api/admin/settings" -> admin.openSettings()
            "/api/admin/release" -> admin.release()
            "/api/admin/update" -> if (!admin.update()) {
                return HttpResponse.error(
                    409,
                    "The player can only update itself in kiosk mode (device owner). " +
                        "Otherwise install the new version on the player phone by hand.",
                )
            }
        }
        return HttpResponse(200, status().toString())
    }

    private fun status(): JSONObject = player.status().apply {
        put("app", APP_ID)
        put("paired", true)
        put("volume", volume.level)
        put("maxVolume", volume.max)
        put("version", admin.version)
        put("deviceOwner", admin.isDeviceOwner)
        put("update", admin.updateState)
        admin.updateMessage?.let { put("updateMessage", it) }
    }

    private fun notPaired() = HttpResponse(
        401,
        JSONObject()
            .put("app", APP_ID)
            .put("paired", false)
            .put("error", "Not paired: show the player this phone's pairing code")
            .toString(),
    )

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
            "/api/admin/settings" to "POST",
            "/api/admin/release" to "POST",
            "/api/admin/update" to "POST",
        )
    }
}
