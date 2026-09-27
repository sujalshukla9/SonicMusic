package com.sonicmusic.app.features.recommendations.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Cooldown system to prevent repetitive recommendations.
 * Design doc Section 12.
 *
 * Tracks cooldown timestamps per (user, song), (user, artist), (user, album),
 * and enforces genre saturation limits.
 */
class CooldownManager {

    companion object {
        // ═══════════════════════════════════════════
        // COOLDOWN WINDOWS (milliseconds)
        // ═══════════════════════════════════════════
        /** Same song shown but not played */
        const val SONG_SHOWN_WINDOW_MS = 6 * 3600 * 1000L      // 6 hours
        /** Same song played */
        const val SONG_PLAYED_WINDOW_MS = 24 * 3600 * 1000L     // 24 hours
        /** Same artist (any song) */
        const val ARTIST_WINDOW_MS = 3 * 3600 * 1000L           // 3 hours
        /** Same album */
        const val ALBUM_WINDOW_MS = 12 * 3600 * 1000L           // 12 hours

        // ═══════════════════════════════════════════
        // BASE PENALTIES (design doc Section 12)
        // ═══════════════════════════════════════════
        const val SONG_SHOWN_PENALTY = 0.8f
        const val SONG_PLAYED_PENALTY = 0.5f
        const val ARTIST_PENALTY = 0.3f
        const val ALBUM_PENALTY = 0.4f
        const val GENRE_SATURATION_PENALTY = 0.3f

        /** Genre saturation threshold: >40% of last 20 recs from one genre */
        const val GENRE_SATURATION_THRESHOLD = 0.4f
        const val GENRE_SATURATION_WINDOW = 20

        // ═══════════════════════════════════════════
        // SKIP DECAY
        // ═══════════════════════════════════════════
        /** Fast decay for skip penalty — shouldn't permanently blacklist */
        const val SKIP_DECAY_LAMBDA = 0.15f
        private const val MS_PER_DAY = 86_400_000f
    }

    // Timestamp maps: key → last event time
    private val songShownAt = mutableMapOf<String, Long>()       // songId → timestamp
    private val songPlayedAt = mutableMapOf<String, Long>()      // songId → timestamp
    private val artistShownAt = mutableMapOf<String, Long>()     // artistName → timestamp
    private val albumShownAt = mutableMapOf<String, Long>()      // albumId → timestamp

    // Skip history: songId → list of skip timestamps
    private val skipHistory = mutableMapOf<String, MutableList<Long>>()

    // Recent recommendation genre tracking for saturation check
    private val recentRecGenres = ArrayDeque<String>(GENRE_SATURATION_WINDOW + 5)

    /**
     * Record that a song was shown (recommended) to the user.
     */
    fun recordShown(songId: String, artistName: String, albumId: String? = null) {
        val now = System.currentTimeMillis()
        songShownAt[songId] = now
        artistShownAt[artistName.lowercase()] = now
        if (albumId != null) albumShownAt[albumId] = now
    }

    /**
     * Record that a song was played.
     */
    fun recordPlayed(songId: String, artistName: String, albumId: String? = null) {
        val now = System.currentTimeMillis()
        songPlayedAt[songId] = now
        artistShownAt[artistName.lowercase()] = now
        if (albumId != null) albumShownAt[albumId] = now
    }

    /**
     * Record that a song was skipped.
     */
    fun recordSkip(songId: String) {
        val now = System.currentTimeMillis()
        skipHistory.getOrPut(songId) { mutableListOf() }.add(now)
    }

    /**
     * Track genre of a recommended song for saturation monitoring.
     */
    fun trackRecommendedGenre(genre: String) {
        recentRecGenres.addLast(genre.lowercase())
        while (recentRecGenres.size > GENRE_SATURATION_WINDOW) {
            recentRecGenres.removeFirst()
        }
    }

    /**
     * Calculate the total cooldown penalty for a candidate song.
     * Returns a value in [0, ~1.5+] — higher = more suppressed.
     *
     * CooldownPenalty(scope) = base_penalty · (1 - elapsed_time / window), floored at 0
     */
    fun calculatePenalty(
        songId: String,
        artistName: String,
        albumId: String? = null,
        genre: String? = null
    ): Float {
        val now = System.currentTimeMillis()
        var totalPenalty = 0f

        // Song-level cooldown (shown)
        songShownAt[songId]?.let { showTime ->
            val elapsed = now - showTime
            if (elapsed < SONG_SHOWN_WINDOW_MS) {
                totalPenalty += SONG_SHOWN_PENALTY * max(0f, 1f - elapsed.toFloat() / SONG_SHOWN_WINDOW_MS)
            }
        }

        // Song-level cooldown (played)
        songPlayedAt[songId]?.let { playTime ->
            val elapsed = now - playTime
            if (elapsed < SONG_PLAYED_WINDOW_MS) {
                totalPenalty += SONG_PLAYED_PENALTY * max(0f, 1f - elapsed.toFloat() / SONG_PLAYED_WINDOW_MS)
            }
        }

        // Artist-level cooldown
        artistShownAt[artistName.lowercase()]?.let { artistTime ->
            val elapsed = now - artistTime
            if (elapsed < ARTIST_WINDOW_MS) {
                totalPenalty += ARTIST_PENALTY * max(0f, 1f - elapsed.toFloat() / ARTIST_WINDOW_MS)
            }
        }

        // Album-level cooldown
        if (albumId != null) {
            albumShownAt[albumId]?.let { albumTime ->
                val elapsed = now - albumTime
                if (elapsed < ALBUM_WINDOW_MS) {
                    totalPenalty += ALBUM_PENALTY * max(0f, 1f - elapsed.toFloat() / ALBUM_WINDOW_MS)
                }
            }
        }

        // Genre saturation check
        if (genre != null && recentRecGenres.size >= GENRE_SATURATION_WINDOW) {
            val genreCount = recentRecGenres.count { it == genre.lowercase() }
            val ratio = genreCount.toFloat() / recentRecGenres.size
            if (ratio > GENRE_SATURATION_THRESHOLD) {
                totalPenalty += GENRE_SATURATION_PENALTY
            }
        }

        return totalPenalty
    }

    /**
     * Calculate skip-specific penalty for a song.
     * SkippedPenalty = Σ(skip_weight · decay(t_since_skip))
     */
    fun calculateSkipPenalty(songId: String): Float {
        val now = System.currentTimeMillis()
        val skips = skipHistory[songId] ?: return 0f

        return skips.sumOf { skipTime ->
            val elapsedDays = (now - skipTime) / MS_PER_DAY
            val decayFactor = exp(-SKIP_DECAY_LAMBDA * elapsedDays)
            (0.6 * decayFactor).toDouble()  // 0.6 = mid-skip weight
        }.toFloat()
    }

    /**
     * Check if a song should be hard-filtered (absolute cooldown block).
     */
    fun isHardBlocked(songId: String): Boolean {
        val now = System.currentTimeMillis()
        // Block if shown in last 30 minutes
        songShownAt[songId]?.let { if (now - it < 30 * 60 * 1000L) return true }
        return false
    }

    /**
     * Prune old entries to prevent unbounded memory growth.
     * Call periodically (e.g. once per hour).
     */
    fun pruneOldEntries() {
        val cutoff = System.currentTimeMillis() - 48 * 3600 * 1000L // 48 hours
        songShownAt.entries.removeAll { it.value < cutoff }
        songPlayedAt.entries.removeAll { it.value < cutoff }
        artistShownAt.entries.removeAll { it.value < cutoff }
        albumShownAt.entries.removeAll { it.value < cutoff }
        skipHistory.values.forEach { skips ->
            skips.removeAll { it < cutoff }
        }
        skipHistory.entries.removeAll { it.value.isEmpty() }
    }
}
