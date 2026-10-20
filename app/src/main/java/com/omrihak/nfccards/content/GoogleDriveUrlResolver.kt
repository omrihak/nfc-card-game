package com.omrihak.nfccards.content

import java.net.URI

/**
 * The only place that knows about Google Drive.
 *
 * A Drive "share" link (`drive.google.com/file/d/<ID>/view`) is an HTML viewer page, not the file.
 * For files shared as "Anyone with the link" the bytes can be fetched, without authentication, from
 * `drive.usercontent.google.com/download?id=<ID>&export=download`.
 *
 * Large files (roughly 100 MB+) may instead answer with an HTML "can't scan for viruses" page;
 * [downloadCandidates] therefore also offers a `confirm=t` variant to try next.
 *
 * Non-Drive URLs pass through untouched, so moving to other hosting later needs no app changes.
 */
object GoogleDriveUrlResolver {

    private val DRIVE_HOSTS = setOf("drive.google.com", "drive.usercontent.google.com")
    private val PATH_ID = Regex("/d/([A-Za-z0-9_-]{10,})")
    private val QUERY_ID = Regex("(?:^|&)id=([A-Za-z0-9_-]{10,})")

    fun isDriveUrl(url: String): Boolean = hostOf(url) in DRIVE_HOSTS

    /** The Drive file id of a share/open/uc/download URL, or null if there is none (e.g. a folder). */
    fun extractFileId(url: String): String? {
        val uri = parse(url) ?: return null
        if (uri.host?.lowercase() !in DRIVE_HOSTS) return null
        return PATH_ID.find(uri.rawPath.orEmpty())?.groupValues?.get(1)
            ?: QUERY_ID.find(uri.rawQuery.orEmpty())?.groupValues?.get(1)
    }

    /**
     * URLs to try, in order, to download [url]. Throws [ContentException] (INVALID_URL) for a Drive
     * URL from which no file id can be extracted.
     */
    fun downloadCandidates(url: String): List<String> {
        if (!isDriveUrl(url)) return listOf(url)
        val id = extractFileId(url)
            ?: throw ContentException(ErrorKind.INVALID_URL, "No Drive file id in: $url")
        val base = "https://drive.usercontent.google.com/download?id=$id&export=download"
        return listOf(base, "$base&confirm=t")
    }

    private fun hostOf(url: String): String? = parse(url)?.host?.lowercase()

    private fun parse(url: String): URI? = try {
        URI(url.trim())
    } catch (e: Exception) {
        null
    }
}
