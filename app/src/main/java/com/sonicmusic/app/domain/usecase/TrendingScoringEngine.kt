package com.sonicmusic.app.domain.usecase

import com.sonicmusic.app.domain.model.Song
import kotlin.math.min

/**
 * Pure scoring logic for the Trending section.
 *
 * Since the app doesn't own play-count infrastructure, YouTube innertube
 * provides the raw "trending" candidates. This engine applies personalized
 * re-ranking so the user sees trending songs relevant to their taste first.
 *
 * Personalization factors (from PRD §2.7):
 *   - Genre affinity boost  (1.3×)
 *   - Language boost         (1.2×)
 *   - Artist familiarity     (1.4×)
 *   - Already-played penalty (0.7×)
 *   - Diversity filter       (max 3 per artist)
 */
object TrendingScoringEngine {

    const val MAX_SONGS_PER_ARTIST = 3

    // ══════════════════════════════════════════
    // PERSONALIZED RE-RANKING
    // ══════════════════════════════════════════

    data class PersonalizedTrending(
        val song: Song,
        val personalizedScore: Double,
        val originalRank: Int
    )

    /**
     * Re-rank a list of trending songs for the given user signals.
     *
     * @param trending        raw trending songs from YouTube (ordered by YouTube's rank)
     * @param userTopGenres   user's top genres (ordered by preference)
     * @param userTopArtists  user's top artists (ordered by play count)
     * @param userLanguages   preferred languages (e.g. ["English"])
     * @param playedSongIds   set of song IDs the user has already played
     */
    fun personalizeAndRank(
        trending: List<Song>,
        userTopGenres: List<String>,
        userTopArtists: List<String>,
        userLanguages: List<String>,
        playedSongIds: Set<String>,
        artistGenreMap: Map<String, List<String>> = emptyMap()
    ): List<Song> {
        if (trending.isEmpty()) return emptyList()

        val scored = trending.mapIndexed { index, song ->
            val score = personalizeScore(
                song = song,
                originalRank = index,
                totalCount = trending.size,
                userTopGenres = userTopGenres,
                userTopArtists = userTopArtists,
                userLanguages = userLanguages,
                playedSongIds = playedSongIds,
                artistGenreMap = artistGenreMap
            )
            PersonalizedTrending(song, score, index)
        }

        val sorted = scored.sortedByDescending { it.personalizedScore }
        val diverse = applyDiversity(sorted)
        return diverse.map { it.song }
    }

    /**
     * Score a single trending song with personalization multipliers.
     */
    fun personalizeScore(
        song: Song,
        originalRank: Int,
        totalCount: Int,
        userTopGenres: List<String>,
        userTopArtists: List<String>,
        userLanguages: List<String>,
        playedSongIds: Set<String>,
        artistGenreMap: Map<String, List<String>>
    ): Double {
        // Base score from position (higher rank = higher base)
        val base = if (totalCount > 0) {
            1.0 - (originalRank.toDouble() / totalCount)
        } else 0.5

        // Genre affinity boost
        val songGenres = inferGenresForSong(song, artistGenreMap)
        val genreBoost = if (songGenres.any { genre ->
            userTopGenres.any { it.equals(genre, ignoreCase = true) }
        }) 1.3 else 1.0

        // Language boost
        val langBoost = if (isLikelyMatchingLanguage(song, userLanguages)) 1.2 else 1.0

        // Artist familiarity boost
        val artistBoost = if (userTopArtists.any {
            it.equals(song.artist, ignoreCase = true)
        }) 1.4 else 1.0

        // Already played penalty
        val novelty = if (playedSongIds.contains(song.id)) 0.7 else 1.0

        return base * genreBoost * langBoost * artistBoost * novelty
    }

    // ══════════════════════════════════════════
    // DIVERSITY FILTER
    // ══════════════════════════════════════════

    /**
     * Max [MAX_SONGS_PER_ARTIST] songs per artist in the trending list.
     */
    fun applyDiversity(ranked: List<PersonalizedTrending>): List<PersonalizedTrending> {
        val artistCount = mutableMapOf<String, Int>()
        return ranked.filter { item ->
            val key = item.song.artist.lowercase().trim()
            val count = artistCount.getOrDefault(key, 0)
            if (count >= MAX_SONGS_PER_ARTIST) return@filter false
            artistCount[key] = count + 1
            true
        }
    }

    // ══════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════

    /**
     * Infer genres for a song based on its artist using the genre map.
     */
    private fun inferGenresForSong(
        song: Song,
        artistGenreMap: Map<String, List<String>>
    ): List<String> {
        val artistLower = song.artist.lowercase()
        return artistGenreMap.entries
            .filter { (key, _) -> key.lowercase() in artistLower || artistLower in key.lowercase() }
            .flatMap { it.value }
            .ifEmpty { listOf("Pop") }
    }

    /**
     * Heuristic language match.
     */
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
