package com.sonicmusic.app.features.queue.manager

import android.net.Uri
import android.util.Log
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.SongRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamUrlCache @Inject constructor(
    private val songRepository: SongRepository
) {
    companion object {
        private const val TAG = "SonicStreamUrlCache"
        const val PRERESOLVE_AHEAD = 2
    }

    data class CachedUrl(
        val url: String,
        val expiresAtMs: Long
    )

    private val cache = ConcurrentHashMap<String, CachedUrl>()

    /**
     * Gets a resolved stream URL from cache if valid; otherwise resolves and caches it.
     */
    suspend fun getStreamUrl(videoId: String): String? {
        val cached = cache[videoId]
        if (cached != null && cached.expiresAtMs > System.currentTimeMillis()) {
            Log.d(TAG, "Cache hit for $videoId")
            return cached.url
        }

        Log.d(TAG, "Cache miss or expired for $videoId. Resolving...")
        val result = songRepository.getStreamUrl(videoId, StreamQuality.BEST)
        return if (result.isSuccess) {
            val url = result.getOrThrow()
            val expiresAtMs = extractExpiry(url)
            cache[videoId] = CachedUrl(url, expiresAtMs)
            url
        } else {
            Log.e(TAG, "Failed to resolve stream URL for $videoId", result.exceptionOrNull())
            null
        }
    }

    /**
     * Pre-resolves stream URLs for the next [PRERESOLVE_AHEAD] items in the queue in the background.
     */
    fun preResolveAhead(
        songs: List<QueueSong>,
        currentIndex: Int,
        scope: CoroutineScope,
        onResolved: (String) -> Unit
    ) {
        val nextSongs = songs.drop(currentIndex + 1).take(PRERESOLVE_AHEAD)
        Log.d(TAG, "preResolveAhead - checking ${nextSongs.size} songs ahead of index $currentIndex")

        for (song in nextSongs) {
            val videoId = song.videoId
            val cached = cache[videoId]

            if (cached != null && cached.expiresAtMs > System.currentTimeMillis()) {
                if (!song.isStreamUrlResolved) {
                    onResolved(videoId)
                }
                continue
            }

            // Launch background task to pre-resolve stream URL
            scope.launch(Dispatchers.IO) {
                try {
                    val result = songRepository.getStreamUrl(videoId, StreamQuality.BEST)
                    if (result.isSuccess) {
                        val url = result.getOrThrow()
                        val expiresAtMs = extractExpiry(url)
                        cache[videoId] = CachedUrl(url, expiresAtMs)
                        Log.d(TAG, "Pre-resolved URL for $videoId successfully")
                        onResolved(videoId)
                    } else {
                        Log.e(TAG, "Failed to pre-resolve URL for $videoId", result.exceptionOrNull())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in background pre-resolve for $videoId", e)
                }
            }
        }
    }

    /**
     * Extracts expiry timestamp from YouTube URL parameter 'expire'.
     * Falls back to a 6-hour expiration if the parameter is absent.
     */
    private fun extractExpiry(url: String): Long {
        return try {
            val uri = Uri.parse(url)
            val expireStr = uri.getQueryParameter("expire")
            if (expireStr != null) {
                expireStr.toLongOrNull()?.times(1000L) ?: (System.currentTimeMillis() + 6 * 3600 * 1000L)
            } else {
                System.currentTimeMillis() + 6 * 3600 * 1000L
            }
        } catch (e: Exception) {
            System.currentTimeMillis() + 6 * 3600 * 1000L
        }
    }

    /**
     * Clears all cached stream URLs.
     */
    fun clear() {
        cache.clear()
        Log.d(TAG, "Cache cleared")
    }
}
