package com.sonicmusic.app.data.remote.source

import android.util.Log
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.StreamQuality
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory TTL cache for resolved YouTube adaptive stream URLs.
 *
 * Key  : "$videoId:${qualityTier.name}"
 * TTL  : 5h55m (YouTube CDN URLs expire in ~6 hours; 5-minute safety buffer)
 *
 * Quality-tier is part of the key so HIGH and MEDIUM resolutions for the
 * same song can coexist in cache independently.
 */
@Singleton
class StreamUrlCache @Inject constructor() {

    companion object {
        private const val TAG = " SonicStream"

        /** 5 hours 55 minutes — 5-minute safety buffer before YouTube's ~6 h URL TTL */
        private const val TTL_MS = (5 * 60 + 55) * 60 * 1000L
    }

    data class CachedEntry(
        val videoId: String,
        val streamUrl: String,
        val itag: Int,
        val streamInfo: AudioStreamInfo,
        val qualityTier: StreamQuality,
        val expiresAtMs: Long,
        val resolvedAtMs: Long = System.currentTimeMillis(),
    ) {
        val isExpired: Boolean get() = System.currentTimeMillis() >= expiresAtMs

        /** Remaining TTL for debug display */
        fun remainingMs(): Long = maxOf(0L, expiresAtMs - System.currentTimeMillis())
    }

    private val cache = ConcurrentHashMap<String, CachedEntry>()

    private fun cacheKey(videoId: String, tier: StreamQuality) = "$videoId:${tier.name}"

    /**
     * Return a cached entry if present and not expired, or null.
     */
    fun get(videoId: String, tier: StreamQuality): CachedEntry? {
        val key = cacheKey(videoId, tier)
        val entry = cache[key] ?: return null
        if (entry.isExpired) {
            cache.remove(key)
            Log.d(TAG, " Cache expired for $videoId (${tier.name})")
            return null
        }
        val remainingMinutes = entry.remainingMs() / 60_000
        Log.d(TAG, " Cache hit: $videoId (${tier.name}) — expires in ${remainingMinutes}m")
        return entry
    }

    /**
     * Store a resolved stream URL in cache.
     */
    fun put(
        videoId: String,
        tier: StreamQuality,
        streamUrl: String,
        itag: Int,
        streamInfo: AudioStreamInfo,
    ) {
        val key = cacheKey(videoId, tier)
        val entry = CachedEntry(
            videoId = videoId,
            streamUrl = streamUrl,
            itag = itag,
            streamInfo = streamInfo,
            qualityTier = tier,
            expiresAtMs = System.currentTimeMillis() + TTL_MS,
        )
        cache[key] = entry
        Log.d(TAG, " Cached: $videoId (${tier.name}) itag=$itag — TTL 5h55m")
    }

    /**
     * Invalidate ALL cached tiers for a given videoId.
     * Called on 403 playback error to force fresh stream re-resolution.
     */
    fun invalidate(videoId: String) {
        val removed = StreamQuality.entries.count { tier ->
            cache.remove(cacheKey(videoId, tier)) != null
        }
        if (removed > 0) {
            Log.d(TAG, " Cache invalidated for $videoId ($removed tier(s) cleared)")
        }
    }

    /**
     * Invalidate a single tier entry.
     */
    fun invalidate(videoId: String, tier: StreamQuality) {
        if (cache.remove(cacheKey(videoId, tier)) != null) {
            Log.d(TAG, " Cache invalidated for $videoId (${tier.name})")
        }
    }

    /** Current number of live cache entries. */
    val size: Int get() = cache.size

    /** Remove all expired entries. */
    fun evictExpired() {
        val before = cache.size
        cache.entries.removeIf { it.value.isExpired }
        val removed = before - cache.size
        if (removed > 0) Log.d(TAG, " Evicted $removed expired cache entries")
    }
}
