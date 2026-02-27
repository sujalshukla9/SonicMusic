package com.sonicmusic.app.domain.usecase

import com.sonicmusic.app.domain.model.Song
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Pure scoring and mixing logic for the Quick Picks section.
 *
 * Quick Picks = 60% familiar tracks + 40% discovery tracks,
 * interleaved in an F-F-D pattern, with diversity constraints
 * and per-session windowed shuffling.
 */
object QuickPicksScoringEngine {

    // ── Weight defaults (from PRD §1.5) ──
    private const val W_BASE       = 0.30
    private const val W_GENRE      = 0.20
    private const val W_ARTIST     = 0.15
    private const val W_POPULARITY = 0.10
    private const val W_LANGUAGE   = 0.10
    private const val W_FRESHNESS  = 0.10
    private const val W_DURATION   = 0.05

    private const val HALF_LIFE_DAYS_ARTIST = 30.0
    private const val HALF_LIFE_DAYS_TRACK  = 14.0
    private const val LN2 = 0.693147

    // ── Diversity constraints ──
    const val MAX_SONGS_PER_ARTIST = 3
    const val MAX_SONGS_PER_GENRE  = 8

    // ── Familiar / Discovery ratio ──
    const val FAMILIAR_RATIO = 0.60

    /**
     * Candidate wrapper used during the scoring pipeline.
     */
    data class ScoredCandidate(
        val song: Song,
        val source: CandidateSource,
        val sourceScore: Double,
        val isFamiliar: Boolean,
        var finalScore: Double = 0.0,
        val inferredGenre: String = "",
        val inferredArtistRank: Int = Int.MAX_VALUE
    )

    enum class CandidateSource {
        FAMILIAR,
        SAME_ARTIST_UNPLAYED,
        SIMILAR_ARTIST,
        GENRE_POPULAR,
        TRENDING_GENRE
    }

    // ══════════════════════════════════════════
    // SCORING
    // ══════════════════════════════════════════

    /**
     * Score a single candidate against the user's taste signals.
     *
     * @param candidate       the candidate to score
     * @param userTopGenres   ordered list of user's top genres (index = rank)
     * @param userTopArtists  ordered list of user's top artists (index = rank)
     * @param userLanguages   list of preferred language tags (e.g. "English")
     */
    fun scoreCandidate(
        candidate: ScoredCandidate,
        userTopGenres: List<String>,
        userTopArtists: List<String>,
        userLanguages: List<String>
    ): Double {
        val base = candidate.sourceScore.coerceIn(0.0, 1.0)

        // Genre match bonus
        val genreBonus = if (candidate.inferredGenre.isNotBlank()) {
            val idx = userTopGenres.indexOfFirst {
                it.equals(candidate.inferredGenre, ignoreCase = true)
            }
            if (idx >= 0) max(0.0, 1.0 - idx.toDouble() / userTopGenres.size) else 0.0
        } else 0.0

        // Artist familiarity
        val artistBonus = run {
            val idx = userTopArtists.indexOfFirst {
                it.equals(candidate.song.artist, ignoreCase = true)
            }
            if (idx >= 0) max(0.0, 1.0 - idx.toDouble() / userTopArtists.size) else 0.0
        }

        // Popularity heuristic (viewCount → 0-1 via log scale, caps at 10M)
        val popularity = if ((candidate.song.viewCount ?: 0) > 0) {
            min(1.0, Math.log10(candidate.song.viewCount!!.toDouble()) / 7.0)
        } else 0.5

        // Language match
        val langMatch = if (userLanguages.isEmpty()) 1.0
        else if (isLikelyMatchingLanguage(candidate.song, userLanguages)) 1.0
        else 0.3

        // Freshness bonus (discovery tracks get full bonus; familiar are neutral)
        val freshness = if (candidate.isFamiliar) 0.5 else 1.0

        // Duration match (heuristic — prefer 3–5-minute songs)
        val durationSec = candidate.song.duration
        val durationMatch = if (durationSec in 120..360) 1.0
        else if (durationSec in 60..600) 0.7
        else 0.4

        return (W_BASE * base +
                W_GENRE * genreBonus +
                W_ARTIST * artistBonus +
                W_POPULARITY * popularity +
                W_LANGUAGE * langMatch +
                W_FRESHNESS * freshness +
                W_DURATION * durationMatch).coerceIn(0.0, 1.0)
    }

    // ══════════════════════════════════════════
    // MIXING & ASSEMBLY
    // ══════════════════════════════════════════

    /**
     * Assemble a Quick Picks list from scored candidates.
     *
     * 1. Separate into familiar / discovery pools
     * 2. Apply diversity constraints
     * 3. Interleave with F-F-D pattern
     * 4. Apply session-based windowed shuffle
     */
    fun assemble(
        candidates: List<ScoredCandidate>,
        targetCount: Int = 25,
        sessionSeed: Long = System.currentTimeMillis()
    ): List<Song> {
        val familiar = candidates
            .filter { it.isFamiliar }
            .sortedByDescending { it.finalScore }

        val discovery = candidates
            .filter { !it.isFamiliar }
            .sortedByDescending { it.finalScore }

        val familiarCount = (targetCount * FAMILIAR_RATIO).toInt()
        val discoveryCount = targetCount - familiarCount

        // Apply diversity constraints per pool
        val familiarFiltered = applyDiversity(familiar).take(familiarCount)
        val discoveryFiltered = applyDiversity(discovery).take(discoveryCount)

        // Interleave
        val interleaved = interleaveByType(
            familiarFiltered.map { it.song },
            discoveryFiltered.map { it.song }
        )

        // Session-based windowed shuffle
        return windowShuffle(interleaved, windowSize = 5, seed = sessionSeed)
    }

    /**
     * Apply diversity constraints: max songs per artist and per genre.
     */
    fun applyDiversity(candidates: List<ScoredCandidate>): List<ScoredCandidate> {
        val usedArtists = mutableMapOf<String, Int>()
        val usedGenres = mutableMapOf<String, Int>()
        return candidates.filter { c ->
            val artistKey = c.song.artist.lowercase().trim()
            val genreKey = c.inferredGenre.lowercase().trim()
            val artistCount = usedArtists.getOrDefault(artistKey, 0)
            val genreCount = usedGenres.getOrDefault(genreKey, 0)
            if (artistCount >= MAX_SONGS_PER_ARTIST) return@filter false
            if (genreKey.isNotBlank() && genreCount >= MAX_SONGS_PER_GENRE) return@filter false
            usedArtists[artistKey] = artistCount + 1
            if (genreKey.isNotBlank()) usedGenres[genreKey] = genreCount + 1
            true
        }
    }

    /**
     * Interleave familiar and discovery songs with F, F, D pattern.
     */
    fun interleaveByType(familiar: List<Song>, discovery: List<Song>): List<Song> {
        val result = mutableListOf<Song>()
        var fIdx = 0
        var dIdx = 0
        val pattern = booleanArrayOf(true, true, false) // F, F, D
        var pIdx = 0

        while (fIdx < familiar.size || dIdx < discovery.size) {
            val wantFamiliar = pattern[pIdx % pattern.size]
            when {
                wantFamiliar && fIdx < familiar.size -> {
                    result.add(familiar[fIdx++])
                }
                dIdx < discovery.size -> {
                    result.add(discovery[dIdx++])
                }
                fIdx < familiar.size -> {
                    result.add(familiar[fIdx++])
                }
            }
            pIdx++
        }
        return result
    }

    /**
     * Shuffles within fixed-size windows for controlled randomness per session.
     */
    fun windowShuffle(songs: List<Song>, windowSize: Int = 5, seed: Long): List<Song> {
        val result = songs.toMutableList()
        val rng = java.util.Random(seed)
        var i = 0
        while (i < result.size) {
            val end = min(i + windowSize, result.size)
            val window = result.subList(i, end)
            for (j in window.indices.reversed()) {
                val k = rng.nextInt(j + 1)
                val tmp = window[j]
                window[j] = window[k]
                window[k] = tmp
            }
            i += windowSize
        }
        return result
    }

    // ══════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════

    /**
     * Recency-weighted artist score. More recent = higher weight.
     * Half-life of 30 days.
     */
    fun artistRecencyScore(playedAtMillis: Long, nowMillis: Long): Double {
        val daysAgo = (nowMillis - playedAtMillis).toDouble() / 86_400_000.0
        if (daysAgo <= 0) return 1.0
        return exp(-LN2 * daysAgo / HALF_LIFE_DAYS_ARTIST).coerceIn(0.0, 1.0)
    }

    /**
     * Recency-weighted track score. Half-life of 14 days.
     */
    fun trackRecencyScore(playedAtMillis: Long, nowMillis: Long): Double {
        val daysAgo = (nowMillis - playedAtMillis).toDouble() / 86_400_000.0
        if (daysAgo <= 0) return 1.0
        return exp(-LN2 * daysAgo / HALF_LIFE_DAYS_TRACK).coerceIn(0.0, 1.0)
    }

    /**
     * Heuristic language match — checks if the song title/artist contains
     * enough Latin characters to likely be in a Latin-script language.
     */
    private fun isLikelyMatchingLanguage(song: Song, preferredLanguages: List<String>): Boolean {
        // For now, assume "English" preference means Latin-script songs are fine
        val text = "${song.title} ${song.artist}"
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return true
        val latinCount = letters.count {
            Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN
        }
        return latinCount.toFloat() / letters.length >= 0.8f
    }
}
