package com.omrihak.nfccards.companion.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The newest published version of the apps, from the GitHub release the player updates itself from. */
object LatestRelease {

    private const val URL_LATEST = "https://api.github.com/repos/omrihak/nfc-card-game/releases/latest"

    /** The latest release's version, e.g. "1.4.2"; null if it can't be found out (offline, rate limit, no release). */
    suspend fun fetch(): String? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(URL_LATEST).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                if (connection.responseCode != 200) return@withContext null
                parseTag(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        } catch (e: java.io.IOException) {
            null
        }
    }

    /** `{"tag_name":"v1.4.2",...}` -> "1.4.2". */
    fun parseTag(json: String): String? =
        try {
            JSONObject(json).optString("tag_name").removePrefix("v").ifEmpty { null }
        } catch (e: org.json.JSONException) {
            null
        }

    /** Whether [latest] is a higher version than [current] ("1.10.0" > "1.9.3"). Unreadable versions are never newer. */
    fun isNewer(latest: String, current: String?): Boolean {
        val a = parts(latest) ?: return false
        val b = current?.let(::parts) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(version: String): List<Int>? =
        version.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
}
