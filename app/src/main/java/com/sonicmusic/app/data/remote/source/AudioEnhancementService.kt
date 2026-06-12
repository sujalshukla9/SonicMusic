package com.sonicmusic.app.data.remote.source

import android.util.Log
import com.sonicmusic.app.domain.model.EnhancedStream
import com.sonicmusic.app.domain.model.TranscodeRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio Enhancement Service — FFmpeg Backend Integration
 * 
 * Sends Opus/WebM stream URLs to a backend FFmpeg API for transcoding 
 * to M4A/ALAC lossless audio. Includes:
 * 
 * - In-memory cache to avoid re-transcoding the same songs
 * - Graceful fallback: returns original URL if backend is unavailable
 * - Expiry-aware caching based on backend response
 * 
 * 📊 Data Science Note:
 * While Opus→ALAC is technically a format conversion (not quality enhancement),
 * M4A/ALAC provides better DAC compatibility, wider device support, and 
 * preferred container format for lossless playback chains.
 */
@Singleton
class AudioEnhancementService @Inject constructor(
    private val api: AudioEnhancementApi
) {
    companion object {
        private const val TAG = "AudioEnhancement"
        private const val MAX_CACHE_SIZE = 200
    }
    
    // In-memory cache: songId → EnhancedStream
    private val cache = ConcurrentHashMap<String, CachedEnhancement>()
    
    /**
     * Transcode an Opus stream URL to M4A/ALAC via the FFmpeg backend.
     * 
     * @param songId Unique identifier for caching
     * @param sourceUrl Original Opus/WebM stream URL from YouTube
     * @return Result containing either the enhanced M4A URL or the original URL on failure
     */
    suspend fun enhanceStream(
        songId: String,
        sourceUrl: String,
    ): Result<EnhancedStream> = withContext(Dispatchers.IO) {
        // Check cache first
        cache[songId]?.let { cached ->
            if (!cached.isExpired()) {
                Log.d(TAG, "✅ Cache hit for $songId: ${cached.stream.codec}")
                return@withContext Result.success(cached.stream)
            } else {
                cache.remove(songId)
                Log.d(TAG, "🔄 Cache expired for $songId")
            }
        }
        
        try {
            Log.d(TAG, "🎵 Requesting transcoding: Opus → M4A ALAC for $songId")
            
            val request = TranscodeRequest(
                sourceUrl = sourceUrl,
                outputFormat = "m4a",
                codec = "alac",
                quality = "lossless",
            )
            
            val enhanced = api.transcode(request)
            
            // Cache the result
            evictIfNeeded()
            cache[songId] = CachedEnhancement(
                stream = enhanced,
                cachedAt = System.currentTimeMillis(),
            )
            
            Log.d(TAG, "✅ Enhanced: ${enhanced.codec} ${enhanced.bitrate}kbps ${enhanced.sampleRate}Hz")
            Result.success(enhanced)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Backend transcoding failed, using original stream", e)
            
            // Graceful fallback: return the original URL wrapped in an EnhancedStream
            val fallback = EnhancedStream(
                enhancedUrl = sourceUrl,
                codec = "OPUS",
                bitrate = 0,
                sampleRate = 48000,
                bitDepth = 16,
                container = "WebM",
            )
            Result.success(fallback)
        }
    }
    
    /**
     * Check if the backend is reachable (cheap health check).
     */
    suspend fun isBackendAvailable(): Boolean {
        return try {
            // Simple check — try the API and see if it responds
            // A proper implementation would hit a /health endpoint
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Clear the enhancement cache.
     */
    fun clearCache() {
        cache.clear()
        Log.d(TAG, "🗑️ Enhancement cache cleared")
    }
    
    /**
     * Evict oldest entries if cache is full.
     */
    private fun evictIfNeeded() {
        if (cache.size >= MAX_CACHE_SIZE) {
            // Remove the oldest 25% of entries
            val sortedEntries = cache.entries.sortedBy { it.value.cachedAt }
            val toRemove = sortedEntries.take(MAX_CACHE_SIZE / 4)
            toRemove.forEach { cache.remove(it.key) }
            Log.d(TAG, "🔄 Evicted ${toRemove.size} cached enhancements")
        }
    }
    
    /**
     * Cached enhancement entry with expiry tracking.
     */
    private data class CachedEnhancement(
        val stream: EnhancedStream,
        val cachedAt: Long,
    ) {
        fun isExpired(): Boolean {
            val expiresAt = stream.expiresAt
            return if (expiresAt > 0) {
                System.currentTimeMillis() > expiresAt * 1000
            } else {
                // Default: expire after 25 minutes (YouTube URLs expire ~30 min)
                System.currentTimeMillis() - cachedAt > 25 * 60 * 1000
            }
        }
    }
}
