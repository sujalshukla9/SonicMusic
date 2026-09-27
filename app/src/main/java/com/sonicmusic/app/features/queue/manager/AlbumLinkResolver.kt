package com.sonicmusic.app.features.queue.manager

import android.util.Log
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.features.player.presentation.AlbumLinkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlbumLinkResolver @Inject constructor(
    private val youTubeiService: YouTubeiService
) {
    companion object {
        private const val TAG = "SonicAlbumLinkResolver"
        private const val SIMILARITY_THRESHOLD = 0.75
    }

    // In-memory session-scoped cache
    private val cache = ConcurrentHashMap<String, AlbumLinkState>()

    suspend fun resolveAlbumInfo(song: QueueSong): AlbumLinkState = withContext(Dispatchers.IO) {
        val videoId = song.videoId
        
        // 0. Cache Check
        cache[videoId]?.let {
            Log.d(TAG, "Cache hit for videoId=$videoId -> $it")
            return@withContext it
        }

        Log.d(TAG, "Resolving album info for: '${song.title}' by '${song.artists}' (videoId=$videoId)")

        // 2(a) PRIMARY: direct browseId if present
        if (!song.albumBrowseId.isNullOrBlank()) {
            val name = song.albumName ?: "Album"
            val state = AlbumLinkState.Available(song.albumBrowseId, name)
            Log.d(TAG, "Primary resolution: direct browseId found: $name (${song.albumBrowseId})")
            cache[videoId] = state
            return@withContext state
        }

        // 2(b) FALLBACK 1: player endpoint (getSongDetails)
        // Note: YTM PLAYER endpoint genuinely lacks albumBrowseId (MPREb_xxx) in its response.
        // But we check it defensively under microformat.playerMicroformatRenderer just in case.
        Log.d(TAG, "Fallback 1: requesting player response for videoId=$videoId")
        try {
            val detailsResult = youTubeiService.getSongDetails(videoId)
            detailsResult.onSuccess { detailsSong ->
                val browseId = detailsSong.albumBrowseId ?: detailsSong.albumId
                val name = detailsSong.albumName ?: detailsSong.album
                if (!browseId.isNullOrBlank() && !name.isNullOrBlank()) {
                    val state = AlbumLinkState.Available(browseId, name)
                    Log.d(TAG, "Fallback 1 success: found album in player response: $name ($browseId)")
                    cache[videoId] = state
                    return@withContext state
                }
            }
            Log.d(TAG, "Fallback 1 failed: player response genuinely lacks albumBrowseId")
        } catch (e: Exception) {
            Log.e(TAG, "Fallback 1 exception", e)
        }

        // 2(c) FALLBACK 2: next endpoint without radio params (getUpNext)
        Log.d(TAG, "Fallback 2: requesting next endpoint for videoId=$videoId")
        try {
            val nextResult = youTubeiService.getUpNext(videoId)
            nextResult.onSuccess { (songs, _) ->
                // Look for the song itself in the returned list
                val matchedSong = songs.firstOrNull { it.id == videoId }
                val browseId = matchedSong?.albumBrowseId ?: matchedSong?.albumId
                val name = matchedSong?.albumName ?: matchedSong?.album
                
                if (!browseId.isNullOrBlank() && !name.isNullOrBlank()) {
                    val state = AlbumLinkState.Available(browseId, name)
                    Log.d(TAG, "Fallback 2 success: found album via watch-next: $name ($browseId)")
                    cache[videoId] = state
                    return@withContext state
                }
            }
            Log.d(TAG, "Fallback 2 failed: watch-next queue items lack album info")
        } catch (e: Exception) {
            Log.e(TAG, "Fallback 2 exception", e)
        }

        // 2(d) FALLBACK 3: Targeted Search for Albums
        val targetAlbumName = song.albumName ?: ""
        val searchTarget = if (targetAlbumName.isNotBlank()) targetAlbumName else song.title
        Log.d(TAG, "Fallback 3: searching targeted album query: '$searchTarget ${song.artists}'")
        try {
            val searchResult = youTubeiService.searchAlbums("$searchTarget ${song.artists}")
            searchResult.onSuccess { albums ->
                val topAlbum = albums.firstOrNull()
                if (topAlbum != null) {
                    val albumTitle = topAlbum.title
                    val albumId = topAlbum.albumId ?: topAlbum.id
                    
                    val similarity = calculateSimilarity(
                        albumTitle.lowercase(),
                        searchTarget.lowercase()
                    )
                    
                    Log.d(TAG, "Similarity between searched '$albumTitle' and target '$searchTarget' is $similarity")
                    if (similarity >= SIMILARITY_THRESHOLD && !albumId.isNullOrBlank()) {
                        val state = AlbumLinkState.Available(albumId, albumTitle)
                        Log.d(TAG, "Fallback 3 success: high confidence match: $albumTitle ($albumId)")
                        cache[videoId] = state
                        return@withContext state
                    } else {
                        Log.d(TAG, "Fallback 3 match below threshold ($similarity < $SIMILARITY_THRESHOLD)")
                    }
                }
            }
            Log.d(TAG, "Fallback 3 failed: no matching albums found in search")
        } catch (e: Exception) {
            Log.e(TAG, "Fallback 3 exception", e)
        }

        Log.w(TAG, "All album resolution paths failed for videoId=$videoId")
        val finalState = AlbumLinkState.Unavailable
        cache[videoId] = finalState
        return@withContext finalState
    }

    private fun calculateSimilarity(s1: String, s2: String): Double {
        val clean1 = s1.filter { it.isLetterOrDigit() }
        val clean2 = s2.filter { it.isLetterOrDigit() }
        val longer = if (clean1.length > clean2.length) clean1 else clean2
        val shorter = if (clean1.length > clean2.length) clean2 else clean1
        val longerLength = longer.length
        if (longerLength == 0) return 1.0
        val editDistance = editDistance(longer, shorter)
        return (longerLength - editDistance) / longerLength.toDouble()
    }

    private fun editDistance(s1: String, s2: String): Int {
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                if (s1[i - 1] == s2[j - 1]) {
                    dp[j] = prev
                } else {
                    dp[j] = minOf(dp[j - 1], dp[j], prev) + 1
                }
                prev = temp
            }
        }
        return dp[s2.length]
    }
}
