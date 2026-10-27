package com.omrihak.nfccards.content

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.omrihak.nfccards.media.MediaCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/**
 * Turns the URL read from a card into content that is fully on local disk:
 * card URL -> metadata JSON -> media (+ artwork) files.
 *
 * Offline-first: metadata that is already cached is used immediately and refreshed in the background
 * (picked up on the next scan); media and artwork are cached forever by URL.
 *
 * A card that was scanned once while online must keep working offline, so:
 * - downloads run in [backgroundScope], not in the scan: tapping another card or stopping mid-download
 *   does not throw away the half-cached card;
 * - a background refresh only replaces the cached metadata once the media it points to is on disk.
 */
class ContentResolver(
    private val cache: MediaCache,
    private val backgroundScope: CoroutineScope,
) {

    private val inFlight = HashMap<String, Deferred<File>>()

    suspend fun resolve(cardUrl: String): ResolvedContent = withContext(Dispatchers.IO) {
        requireHttpUrl(cardUrl)
        val metadata = loadMetadata(cardUrl)
        Log.i(TAG, "Resolving ${metadata.type} from ${metadata.media}")

        when (metadata.type) {
            ContentMetadata.TYPE_AUDIO -> coroutineScope {
                val media = async { fetch(metadata.media) }
                // Artwork is optional: any failure just means the default artwork is shown.
                val artwork = async { metadata.image?.let { loadArtwork(it) } }
                ResolvedContent.Audio(media.await(), artwork.await(), metadata.media)
            }
            ContentMetadata.TYPE_VIDEO -> ResolvedContent.Video(fetch(metadata.media), metadata.media)
            else -> throw ContentException(ErrorKind.UNSUPPORTED, "Unsupported content type ${metadata.type}")
        }
    }

    /**
     * Re-downloads a cached media file, e.g. after the player could not decode it. The old file is only
     * replaced once the new one is complete, so when offline the card keeps whatever it had.
     */
    fun redownload(mediaUrl: String) {
        backgroundScope.launch {
            try {
                cache.refresh(mediaUrl) {}
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Re-download skipped for $mediaUrl: ${e.message}")
            }
        }
    }

    private suspend fun loadMetadata(cardUrl: String): ContentMetadata {
        cache.cached(cardUrl)?.let { file ->
            try {
                val metadata = ContentMetadata.parse(file.readText())
                refreshInBackground(cardUrl)
                return metadata
            } catch (e: ContentException) {
                Log.w(TAG, "Cached metadata for $cardUrl is unusable (${e.kind}); re-fetching")
                cache.evict(cardUrl)
            }
        }

        val file = try {
            fetch(cardUrl)
        } catch (e: ContentException) {
            if (e.kind == ErrorKind.NO_INTERNET || e.kind == ErrorKind.INVALID_URL) throw e
            throw ContentException(ErrorKind.METADATA_UNAVAILABLE, "Could not fetch metadata from $cardUrl", e)
        }
        val metadata = try {
            ContentMetadata.parse(file.readText())
        } catch (e: ContentException) {
            cache.evict(cardUrl)
            throw e
        }
        // Start the media downloads right away, so they finish even if this scan is cancelled first.
        prefetch(metadata)
        return metadata
    }

    private fun refreshInBackground(cardUrl: String) {
        backgroundScope.launch {
            try {
                // The new file replaces the cached one only if it parses and its media is on disk, so a card
                // whose song was changed keeps playing the old one offline until the new one is downloaded.
                cache.refresh(cardUrl) { file ->
                    val metadata = ContentMetadata.parse(file.readText())
                    prefetch(metadata)
                    fetch(metadata.media)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Background metadata refresh skipped for $cardUrl: ${e.message}")
            }
        }
    }

    private fun prefetch(metadata: ContentMetadata) {
        startFetch(metadata.media)
        metadata.image?.let { startFetch(it) }
    }

    /** The cached file for [url], downloading it first if needed. Cancelling the caller does not cancel the download. */
    private suspend fun fetch(url: String): File = cache.cached(url) ?: startFetch(url).await()

    /** Starts (or joins) a download of [url] in [backgroundScope]; failures surface only to whoever awaits it. */
    private fun startFetch(url: String): Deferred<File> = synchronized(inFlight) {
        inFlight[url] ?: backgroundScope.async(start = CoroutineStart.LAZY) { cache.get(url) }.also { download ->
            inFlight[url] = download
            download.invokeOnCompletion { synchronized(inFlight) { inFlight.remove(url, download) } }
            download.start()
        }
    }

    private suspend fun loadArtwork(url: String): Bitmap? {
        return try {
            val file = fetch(url)
            decode(file) ?: run {
                Log.w(TAG, "Artwork $url is not a decodable image")
                cache.evict(url)
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Artwork unavailable ($url): ${e.message}")
            null
        }
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_ARTWORK_PX) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun requireHttpUrl(url: String) {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw ContentException(ErrorKind.INVALID_URL, "Not a valid URL: $url", e)
        }
        val schemeOk = uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)
        if (!schemeOk || uri.host.isNullOrBlank()) {
            throw ContentException(ErrorKind.INVALID_URL, "Not an http(s) URL: $url")
        }
    }

    private companion object {
        const val TAG = "ContentResolver"
        const val MAX_ARTWORK_PX = 2048
    }
}
