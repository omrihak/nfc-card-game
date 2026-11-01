package com.omrihak.nfccards.content

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.omrihak.nfccards.game.CardKey
import com.omrihak.nfccards.game.GameCard
import com.omrihak.nfccards.media.MediaCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 */
class ContentResolver(
    private val cache: MediaCache,
    private val backgroundScope: CoroutineScope,
) {

    suspend fun resolve(cardUrl: String): ResolvedContent = withContext(Dispatchers.IO) {
        requireHttpUrl(cardUrl)
        val metadata = loadMetadata(cardUrl)
        Log.i(TAG, "Resolving ${metadata.type} from ${metadata.media}")

        when (metadata.type) {
            ContentMetadata.TYPE_AUDIO -> coroutineScope {
                val media = async { cache.get(metadata.media) }
                // Artwork is optional: any failure just means the default artwork is shown.
                val artwork = async { metadata.image?.let { loadArtwork(it) } }
                ResolvedContent.Audio(media.await(), artwork.await(), metadata.media, metadata.name)
            }
            ContentMetadata.TYPE_VIDEO -> ResolvedContent.Video(cache.get(metadata.media), metadata.media, metadata.name)
            ContentMetadata.TYPE_GAME -> resolveGame(metadata)
            ContentMetadata.TYPE_WORD ->
                ResolvedContent.Word(metadata.words, metadata.image?.let { loadArtwork(it) }, metadata.name)
            else -> throw ContentException(ErrorKind.UNSUPPORTED, "Unsupported content type ${metadata.type}")
        }
    }

    /**
     * Loads every card of a game's deck (metadata, picture and any recorded words) so the game can run offline.
     * A deck card that cannot be loaded is left out; the game itself decides whether enough cards remain.
     */
    private suspend fun resolveGame(metadata: ContentMetadata): ResolvedContent.Game = coroutineScope {
        val spec = metadata.game ?: throw ContentException(ErrorKind.MALFORMED_METADATA, "Game card without settings")
        val languages = setOf(spec.lang.ask, spec.lang.answer)
        val loaded = spec.deck.map { link -> async { loadDeckCard(link, languages) } }.awaitAll().filterNotNull()
        ResolvedContent.Game(
            spec = spec,
            cards = loaded.map { it.card },
            hints = loaded.mapNotNull { c -> c.artwork?.let { c.card.url to it } }.toMap(),
            clips = loaded.flatMap { it.clips.toList() }.toMap(),
            title = metadata.name,
        )
    }

    private class DeckCard(val card: GameCard, val artwork: Bitmap?, val clips: Map<String, File>)

    private suspend fun loadDeckCard(link: String, languages: Set<String>): DeckCard? = try {
        val metadata = loadMetadata(link)
        val words = metadata.words
        if (metadata.type == ContentMetadata.TYPE_GAME || words.isEmpty) {
            Log.w(TAG, "Deck card $link has no words; skipped")
            null
        } else coroutineScope {
            val artwork = async { metadata.image?.let { loadArtwork(it) } }
            val clips = languages.mapNotNull { words[it]?.audio }.distinct().map { url ->
                async {
                    try {
                        url to cache.get(url)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Recorded word unavailable ($url): ${e.message}; speech is used instead")
                        null
                    }
                }
            }
            DeckCard(GameCard(CardKey.of(link), words), artwork.await(), clips.awaitAll().filterNotNull().toMap())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Deck card $link could not be loaded: ${e.message}")
        null
    }

    /** Drops a cached media file, e.g. after the player could not decode it, so it is re-downloaded next time. */
    fun evict(mediaUrl: String) = cache.evict(mediaUrl)

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
            cache.get(cardUrl)
        } catch (e: ContentException) {
            if (e.kind == ErrorKind.NO_INTERNET || e.kind == ErrorKind.INVALID_URL) throw e
            throw ContentException(ErrorKind.METADATA_UNAVAILABLE, "Could not fetch metadata from $cardUrl", e)
        }
        try {
            return ContentMetadata.parse(file.readText())
        } catch (e: ContentException) {
            cache.evict(cardUrl)
            throw e
        }
    }

    private fun refreshInBackground(cardUrl: String) {
        backgroundScope.launch {
            try {
                // The new file replaces the cached one only if it parses.
                cache.refresh(cardUrl) { ContentMetadata.parse(it.readText()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Background metadata refresh skipped for $cardUrl: ${e.message}")
            }
        }
    }

    private suspend fun loadArtwork(url: String): Bitmap? {
        return try {
            val file = cache.get(url)
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
