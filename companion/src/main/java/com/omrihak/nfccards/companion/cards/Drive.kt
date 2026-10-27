package com.omrihak.nfccards.companion.cards

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DriveFile(val id: String, val name: String)

/** Something to upload: its size and type, and a way to read it. */
class UploadSource(val size: Long, val mimeType: String, val open: () -> InputStream)

/**
 * Minimal Google Drive v3 REST client, the Kotlin twin of the web manager's `drive.js`.
 *
 * Uses only the drive.file scope: the app sees just the files and folders it (or the web manager,
 * which is the same Google Cloud project) created.
 */
class Drive(private val token: suspend () -> String) {

    suspend fun find(name: String, parent: String? = null, mimeType: String? = null): DriveFile? {
        val q = mutableListOf("name = ${quote(name)}", "trashed = false")
        if (parent != null) q += "${quote(parent)} in parents"
        if (mimeType != null) q += "mimeType = ${quote(mimeType)}"
        val query = "q=${encode(q.joinToString(" and "))}&fields=${encode("files(id,name)")}&pageSize=1"
        val files = json("GET", "$API/files?$query").getJSONArray("files")
        return if (files.length() == 0) null else files.getJSONObject(0).toDriveFile()
    }

    suspend fun createFolder(name: String, parent: String? = null): DriveFile {
        val metadata = JSONObject().put("name", name).put("mimeType", FOLDER)
        if (parent != null) metadata.put("parents", listOf(parent).toJsonArray())
        return json("POST", "$API/files?fields=id,name", metadata.toString(), "application/json").toDriveFile()
    }

    suspend fun findOrCreateFolder(name: String): DriveFile =
        find(name, mimeType = FOLDER) ?: createFolder(name)

    /** Resumable upload (works for large videos), reporting progress as a 0..1 fraction. */
    suspend fun upload(
        source: UploadSource,
        name: String,
        parent: String,
        onProgress: (Float) -> Unit = {},
    ): DriveFile = withContext(Dispatchers.IO) {
        val metadata = JSONObject().put("name", name).put("parents", listOf(parent).toJsonArray())
        val start = open("POST", "$UPLOAD/files?uploadType=resumable&fields=id,name")
        val sessionUrl = try {
            start.setRequestProperty("X-Upload-Content-Type", source.mimeType)
            start.setRequestProperty("X-Upload-Content-Length", source.size.toString())
            send(start, metadata.toString().toByteArray(), "application/json; charset=UTF-8")
            check(start)
            start.getHeaderField("Location") ?: throw IOException("Drive did not return an upload URL")
        } finally {
            start.disconnect()
        }

        // The session URL itself authorizes the upload.
        val put = URL(sessionUrl).openConnection() as HttpURLConnection
        try {
            put.requestMethod = "PUT"
            put.doOutput = true
            put.setRequestProperty("Content-Type", source.mimeType)
            put.setFixedLengthStreamingMode(source.size)
            put.connectTimeout = TIMEOUT_MILLIS
            put.readTimeout = UPLOAD_READ_TIMEOUT_MILLIS
            source.open().use { input ->
                put.outputStream.use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var sent = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        sent += n
                        if (source.size > 0) onProgress((sent.toFloat() / source.size).coerceAtMost(1f))
                    }
                }
            }
            check(put, "Upload of $name failed")
            onProgress(1f)
            JSONObject(put.inputStream.bufferedReader().use { it.readText() }).toDriveFile()
        } finally {
            put.disconnect()
        }
    }

    /** "Anyone with the link: Viewer". The player downloads files without signing in. */
    suspend fun makePublic(fileId: String) {
        val body = JSONObject().put("role", "reader").put("type", "anyone").toString()
        json("POST", "$API/files/$fileId/permissions?fields=id", body, "application/json")
    }

    suspend fun readText(fileId: String): String = text("GET", "$API/files/$fileId?alt=media")

    suspend fun overwriteText(fileId: String, text: String, type: String = "application/json") {
        text("PATCH", "$UPLOAD/files/$fileId?uploadType=media&fields=id", text, type)
    }

    /** Moves a file or folder (with everything in it) to the trash. A file that no longer exists counts as done. */
    suspend fun trash(fileId: String) {
        try {
            json("PATCH", "$API/files/$fileId?fields=id", JSONObject().put("trashed", true).toString(), "application/json")
        } catch (e: DriveException) {
            if (e.status != 404) throw e
        }
    }

    private suspend fun json(method: String, url: String, body: String? = null, type: String? = null) =
        JSONObject(text(method, url, body, type))

    private suspend fun text(method: String, url: String, body: String? = null, type: String? = null): String =
        withContext(Dispatchers.IO) {
            val connection = open(method, url)
            try {
                if (body != null) send(connection, body.toByteArray(), type ?: "application/json")
                check(connection)
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }

    private suspend fun open(method: String, url: String): HttpURLConnection {
        val bearer = token()
        val connection = URL(url).openConnection() as HttpURLConnection
        // HttpURLConnection has no PATCH; Google APIs accept the override header.
        if (method == "PATCH") {
            connection.requestMethod = "POST"
            connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        } else {
            connection.requestMethod = method
        }
        connection.setRequestProperty("Authorization", "Bearer $bearer")
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.useCaches = false
        return connection
    }

    private fun send(connection: HttpURLConnection, bytes: ByteArray, type: String) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", type)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
    }

    private fun check(connection: HttpURLConnection, what: String = "Drive") {
        val code = connection.responseCode
        if (code in 200..299) return
        val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        throw DriveException(code, "$what: $code ${body.take(300)}")
    }

    private fun JSONObject.toDriveFile() = DriveFile(getString("id"), optString("name"))

    private fun List<String>.toJsonArray() = JSONArray(this)

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER = "application/vnd.google-apps.folder"
        private const val TIMEOUT_MILLIS = 20_000
        private const val UPLOAD_READ_TIMEOUT_MILLIS = 120_000

        fun quote(value: String) = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

        /** File id from a share link (or a ?id= download link), or null. */
        fun idFromLink(link: String): String? =
            (Regex("/d/([\\w-]+)").find(link) ?: Regex("[?&]id=([\\w-]+)").find(link))?.groupValues?.get(1)

        fun shareLink(fileId: String) = "https://drive.google.com/file/d/$fileId/view?usp=sharing"

        fun folderLink(folderId: String) = "https://drive.google.com/drive/folders/$folderId"
    }
}

class DriveException(val status: Int, message: String) : IOException(message)
