package com.omrihak.nfccards.media

import android.util.Log
import com.omrihak.nfccards.content.ContentException
import com.omrihak.nfccards.content.ErrorKind
import com.omrihak.nfccards.content.GoogleDriveUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

/**
 * Whole-file disk cache keyed by source URL (metadata, audio, video and images alike).
 *
 * Files live in app-private storage (not the OS-purgeable cache dir) so cards keep working offline.
 * Downloads go to a temp file and are renamed into place, so a half-finished download is never served.
 * Nothing is evicted automatically: a toddler toy has a small, bounded set of cards.
 */
class MediaCache(private val dir: File) {

    init {
        dir.mkdirs()
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    fun cached(url: String): File? = fileFor(url).takeIf { it.isFile && it.length() > 0 }

    fun evict(url: String) {
        fileFor(url).delete()
    }

    /** Returns the cached file, downloading it first if needed. */
    suspend fun get(url: String): File = withContext(Dispatchers.IO) {
        cached(url) ?: download(url, validate = null)
    }

    /** Re-downloads [url]; [validate] may throw to reject the new file and keep the old one. */
    suspend fun refresh(url: String, validate: suspend (File) -> Unit): File = withContext(Dispatchers.IO) {
        download(url, validate)
    }

    private suspend fun download(url: String, validate: (suspend (File) -> Unit)?): File {
        val dest = fileFor(url)
        var lastError: Exception? = null
        for (candidate in GoogleDriveUrlResolver.downloadCandidates(url)) {
            try {
                fetchTo(candidate, dest, validate)
                Log.i(TAG, "Downloaded $candidate (${dest.length()} bytes)")
                return dest
            } catch (e: HtmlResponseException) {
                Log.w(TAG, "$candidate answered with an HTML page; trying next candidate")
                lastError = e
            } catch (e: ContentException) {
                throw e
            } catch (e: UnknownHostException) {
                throw ContentException(ErrorKind.NO_INTERNET, "No connection fetching $candidate", e)
            } catch (e: ConnectException) {
                throw ContentException(ErrorKind.NO_INTERNET, "No connection fetching $candidate", e)
            } catch (e: SocketTimeoutException) {
                throw ContentException(ErrorKind.NO_INTERNET, "Timed out fetching $candidate", e)
            } catch (e: IOException) {
                throw ContentException(ErrorKind.DOWNLOAD_FAILED, "Download failed for $candidate: ${e.message}", e)
            }
        }
        throw ContentException(ErrorKind.DOWNLOAD_FAILED, "Only HTML pages were returned for $url", lastError)
    }

    private suspend fun fetchTo(url: String, dest: File, validate: (suspend (File) -> Unit)?) {
        val tmp = File.createTempFile("dl_", ".part", dir)
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NfcCards")
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")

                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var first = true
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (first) {
                                first = false
                                if (looksLikeHtml(buf, n)) throw HtmlResponseException()
                            }
                            out.write(buf, 0, n)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (tmp.length() == 0L) throw IOException("Empty response")
            validate?.invoke(tmp)
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun fileFor(url: String): File = File(dir, sha256(url))

    private fun looksLikeHtml(buf: ByteArray, n: Int): Boolean {
        val head = String(buf, 0, minOf(n, 256), Charsets.ISO_8859_1).trimStart().lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private class HtmlResponseException : IOException("Got HTML instead of a file")

    private companion object {
        const val TAG = "MediaCache"
    }
}
