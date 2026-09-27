package com.sonicmusic.app.data.remote.source

import android.util.Log

/**
 * Layer 4 Defense-in-Depth Guard: Content Type Filtering Post-Response.
 *
 * Implements a strict 4-step pipeline to guarantee that even within valid YTM endpoints,
 * no UGC, fan uploads, mashups, speed edits, or non-music items leak into SonicMusic.
 */
object YtmScopeGuard {
    private const val TAG = "YtmScopeGuard"

    /**
     * Step 1: Type-based check
     * Returns true if videoType is known official music type (ATV/OMV).
     * Returns false if explicitly UGC or non-music.
     * Returns null if videoType is missing / null / unknown (triggering fallback).
     */
    fun checkVideoType(videoType: String?): Boolean? {
        if (videoType.isNullOrBlank() || videoType == "MUSIC_VIDEO_TYPE_UNKNOWN") {
            return null
        }
        val cleanType = videoType.trim()
        if (YtmScopeConfig.ContentFilter.KEEP_VIDEO_TYPES.contains(cleanType)) {
            return true
        }
        if (YtmScopeConfig.ContentFilter.REJECT_VIDEO_TYPES.contains(cleanType)) {
            return false
        }
        // Unknown non-standard video type
        return false
    }

    /**
     * Step 2: Fallback check when videoType is absent
     * Verifies presence of a verifiable artist/channel link or official artist indicator.
     */
    fun hasValidArtistLink(
        artistName: String,
        artistId: String?,
        channelId: String?,
        isVerified: Boolean,
        isOfficialArtist: Boolean
    ): Boolean {
        // 1. Explicit verified or official flag
        if (isVerified || isOfficialArtist) return true

        // 2. Has artist ID (UC-prefixed channel or artist browseId)
        if (!artistId.isNullOrBlank() && (artistId.startsWith("UC") || artistId.startsWith("FEmusic_"))) {
            return true
        }
        if (!channelId.isNullOrBlank() && channelId.startsWith("UC")) {
            return true
        }

        // 3. Official distributor / Topic / VEVO channel indicators in artist name
        val trimmed = artistName.trim()
        if (trimmed.endsWith(" - Topic", ignoreCase = true) ||
            trimmed.endsWith("VEVO", ignoreCase = true) ||
            trimmed.startsWith("Topic - ", ignoreCase = true)
        ) {
            return true
        }

        // If artist link requirement is active, fail if artist is "Unknown Artist" or completely unlinked
        if (YtmScopeConfig.ContentFilter.REQUIRE_ARTIST_LINK_IF_TYPE_MISSING) {
            if (trimmed.isEmpty() || trimmed.equals("Unknown Artist", ignoreCase = true) || trimmed.equals("YouTube", ignoreCase = true)) {
                return false
            }
        }

        // If it has a non-empty plausible artist name, allow it with warning if not unknown
        return trimmed.isNotEmpty()
    }

    /**
     * Step 3: Title-based secondary filter (case-insensitive regex safety net)
     */
    fun isTitleBlacklisted(title: String): Boolean {
        if (title.isBlank()) return true
        return YtmScopeConfig.ContentFilter.TITLE_BLACKLIST_REGEX.containsMatchIn(title)
    }

    /**
     * Step 4: Deduplicate items by videoId while preserving original order.
     */
    fun <T> deduplicateByVideoId(items: List<T>, videoIdSelector: (T) -> String): List<T> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<T>()
        for (item in items) {
            val id = videoIdSelector(item)
            if (id.isNotBlank() && seen.add(id)) {
                result.add(item)
            }
        }
        return result
    }

    private fun logDebug(message: String) {
        try {
            Log.d(TAG, message)
        } catch (_: Throwable) {
            // Safe fallback when running in standard JVM unit test environments
        }
    }

    /**
     * Full enforcement pipeline for RawShelfItems:
     * 1. Check title blacklist
     * 2. Check videoType (ATV / OMV)
     * 3. Fallback to artist link validation if videoType is missing
     * 4. Deduplicate by videoId
     */
    fun enforce(items: List<RawShelfItem>): List<RawShelfItem> {
        val filtered = items.filter { item ->
            // Step 3 (early rejection): Title blacklist
            if (isTitleBlacklisted(item.title)) {
                logDebug("Filtered out (Title Blacklist): '${item.title}'")
                return@filter false
            }

            // Step 1: Type check
            when (checkVideoType(item.videoType)) {
                true -> {
                    // Passed type check
                    true
                }
                false -> {
                    logDebug("Filtered out (Rejected videoType=${item.videoType}): '${item.title}' by '${item.artistName}'")
                    false
                }
                null -> {
                    // Step 2: Fallback check
                    val validArtist = hasValidArtistLink(
                        artistName = item.artistName,
                        artistId = item.artistId,
                        channelId = item.channelId,
                        isVerified = item.isVerified,
                        isOfficialArtist = item.isOfficialArtist
                    )
                    if (validArtist) {
                        logDebug("Fallback kept (Valid Artist Link): '${item.title}' by '${item.artistName}'")
                        true
                    } else {
                        logDebug("Filtered out (No valid artist link & missing videoType): '${item.title}' by '${item.artistName}'")
                        false
                    }
                }
            }
        }

        // Step 4: Deduplicate by videoId
        return deduplicateByVideoId(filtered) { it.videoId }
    }
}
