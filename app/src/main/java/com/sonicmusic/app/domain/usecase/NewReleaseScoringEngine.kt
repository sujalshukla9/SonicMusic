package com.sonicmusic.app.domain.usecase

import com.sonicmusic.app.domain.model.Song
import kotlin.math.min

/**
 * Pure scoring logic for the New Releases section.
 *
 * Ranks newly released songs by personalized relevance:
 *   - Artist relevance   (followed > top listened > similar > unknown)
 *   - Genre match         (user's top genres)
 *   - Popularity signal   (viewCount log-scale)
 *   - Novelty             (already played = penalized)
 *
 * Since the app gets new releases from YouTube innertube (which already
 * filters to recent content), this engine focuses on *personalizing the order*.
 */
object NewReleaseScoringEngine {

    // ── Weights (from PRD §3.4) ──
    private const val W_ARTIST      = 0.30
    private const val W_GENRE       = 0.15
    private const val W_POPULARITY  = 0.15
    private const val W_LANGUAGE    = 0.10
    private const val W_NOVELTY     = 0.10
    // Freshness is implicit (YouTube already returns recent items)

    const val MAX_SONGS_PER_ARTIST = 3

    // ══════════════════════════════════════════
    // SCORING
    // ══════════════════════════════════════════

    /**
     * Score each new release for a specific user and return sorted.
     *
     * @param releases          raw new release songs from YouTube
     * @param userTopArtists    user's top artists by play count
     * @param followedArtistIds set of artist IDs the user follows
     * @param userTopGenres     user's top genres
     * @param userLanguages     preferred languages
     * @param playedSongIds     songs already played
     * @param artistGenreMap    artist → genre lookup
     */
    fun scoreAndRank(
        releases: List<Song>,
        userTopArtists: List<String>,
        followedArtistIds: Set<String>,
        userTopGenres: List<String>,
        userLanguages: List<String>,
        playedSongIds: Set<String>,
        artistGenreMap: Map<String, List<String>> = emptyMap()
    ): List<Song> {
        if (releases.isEmpty()) return emptyList()

        data class Scored(val song: Song, val score: Double)

        val scored = releases.map { song ->
            val score = scoreRelease(
                song = song,
                userTopArtists = userTopArtists,
                followedArtistIds = followedArtistIds,
                userTopGenres = userTopGenres,
                userLanguages = userLanguages,
                playedSongIds = playedSongIds,
                artistGenreMap = artistGenreMap
            )
            Scored(song, score)
        }

        // Sort by score, apply diversity, return
        val sorted = scored.sortedByDescending { it.score }
        return applyDiversity(sorted.map { it.song })
    }

    /**
     * Score a single new release for the user.
     */
    fun scoreRelease(
        song: Song,
        userTopArtists: List<String>,
        followedArtistIds: Set<String>,
        userTopGenres: List<String>,
        userLanguages: List<String>,
        playedSongIds: Set<String>,
        artistGenreMap: Map<String, List<String>>
    ): Double {
        // ── Artist relevance ──
        val artistRelevance = when {
            // Check if artist is followed (by name match against followed IDs/names)
            followedArtistIds.any { id ->
                id.equals(song.artistId, ignoreCase = true) ||
                id.equals(song.artist, ignoreCase = true)
            } -> 1.0
            // Check if artist is in top listened
            userTopArtists.any { it.equals(song.artist, ignoreCase = true) } -> {
                val idx = userTopArtists.indexOfFirst { it.equals(song.artist, ignoreCase = true) }
                0.5 + 0.5 * (1.0 - idx.toDouble() / userTopArtists.size.coerceAtLeast(1))
            }
            // Check if artist genres overlap user genres (similar artist heuristic)
            inferGenresForSong(song, artistGenreMap).any { genre ->
                userTopGenres.any { it.equals(genre, ignoreCase = true) }
            } -> 0.4
            else -> 0.1
        }

        // ── Genre match ──
        val songGenres = inferGenresForSong(song, artistGenreMap)
        val genreMatch = if (songGenres.isEmpty()) 0.3 else {
            val bestIdx = songGenres.mapNotNull { genre ->
                userTopGenres.indexOfFirst { it.equals(genre, ignoreCase = true) }
                    .takeIf { it >= 0 }
            }.minOrNull()
            if (bestIdx != null) {
                1.0 - bestIdx.toDouble() / userTopGenres.size.coerceAtLeast(1)
            } else 0.2
        }

        // ── Popularity signal (viewCount log-scale, caps at ~10M) ──
        val viewCount = song.viewCount ?: 0L
        val popularity = if (viewCount > 0) {
            min(1.0, Math.log10(viewCount.toDouble()) / 7.0)
        } else 0.3

        // ── Language match ──
        val langMatch = if (userLanguages.isEmpty()) 1.0
        else if (isLikelyMatchingLanguage(song, userLanguages)) 1.0
        else 0.5

        // ── Novelty (already played = penalized) ──
        val novelty = if (playedSongIds.contains(song.id)) 0.5 else 1.0

        // ── Final weighted score ──
        return (W_ARTIST * artistRelevance +
                W_GENRE * genreMatch +
                W_POPULARITY * popularity +
                W_LANGUAGE * langMatch +
                W_NOVELTY * novelty).coerceIn(0.0, 1.5)
    }

    // ══════════════════════════════════════════
    // DIVERSITY
    // ══════════════════════════════════════════

    fun applyDiversity(songs: List<Song>): List<Song> {
        val artistCount = mutableMapOf<String, Int>()
        return songs.filter { song ->
            val key = song.artist.lowercase().trim()
            val count = artistCount.getOrDefault(key, 0)
            if (count >= MAX_SONGS_PER_ARTIST) return@filter false
            artistCount[key] = count + 1
            true
        }
    }

    // ══════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════

    private fun inferGenresForSong(
        song: Song,
        artistGenreMap: Map<String, List<String>>
    ): List<String> {
        val artistLower = song.artist.lowercase()
        return artistGenreMap.entries
            .filter { (key, _) -> key.lowercase() in artistLower || artistLower in key.lowercase() }
            .flatMap { it.value }
    }

    private fun isLikelyMatchingLanguage(song: Song, preferredLanguages: List<String>): Boolean {
        if (preferredLanguages.isEmpty()) return true
        val text = "${song.title} ${song.artist}"
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return true
        val latinCount = letters.count {
            Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN
        }
        return latinCount.toFloat() / letters.length >= 0.8f
    }
}
