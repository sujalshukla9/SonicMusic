package com.sonicmusic.app.features.recommendations.engine

/**
 * Feature space definition and song vector builder.
 *
 * MVP hand-crafted features (~40 dimensions):
 *   genres (15) + moods (8) + tempo buckets (5) + languages (5) +
 *   era decades (5) + popularity percentile (1) + artist familiarity (1)
 *
 * @see Design doc Section 3.1, 3.2
 */
object FeatureSpace {

    // ═══════════════════════════════════════════
    // GENRE FEATURES (indices 0..14)
    // ═══════════════════════════════════════════
    val GENRES = listOf(
        "Pop", "Hip-Hop", "R&B", "Rock", "Bollywood",
        "Punjabi", "Classical", "K-Pop", "Alternative", "Dance",
        "Acoustic", "Country", "Trap", "Indie", "Film"
    )
    private val GENRE_INDEX = GENRES.withIndex().associate { (i, g) -> g.lowercase().trim() to i }
    const val GENRE_OFFSET = 0
    const val GENRE_COUNT = 15

    // ═══════════════════════════════════════════
    // MOOD FEATURES (indices 15..22)
    // ═══════════════════════════════════════════
    val MOODS = listOf(
        "Energetic", "Chill", "Sad", "Upbeat",
        "Romantic", "Dark", "Happy", "Aggressive"
    )
    private val MOOD_INDEX = MOODS.withIndex().associate { (i, m) -> m.lowercase().trim() to i }
    const val MOOD_OFFSET = 15
    const val MOOD_COUNT = 8

    // ═══════════════════════════════════════════
    // TEMPO BUCKETS (indices 23..27)
    // ═══════════════════════════════════════════
    // Bucket: <80 BPM, 80-100, 100-120, 120-140, 140+
    const val TEMPO_OFFSET = 23
    const val TEMPO_COUNT = 5

    // ═══════════════════════════════════════════
    // LANGUAGE FEATURES (indices 28..32)
    // ═══════════════════════════════════════════
    val LANGUAGES = listOf("English", "Hindi", "Punjabi", "Korean", "Spanish")
    private val LANG_INDEX = LANGUAGES.withIndex().associate { (i, l) -> l.lowercase().trim() to i }
    const val LANG_OFFSET = 28
    const val LANG_COUNT = 5

    // ═══════════════════════════════════════════
    // ERA/DECADE FEATURES (indices 33..37)
    // ═══════════════════════════════════════════
    // Decades: pre-2000, 2000s, 2010s, 2020-2024, 2025+
    const val ERA_OFFSET = 33
    const val ERA_COUNT = 5

    // ═══════════════════════════════════════════
    // CONTINUOUS FEATURES (indices 38..39)
    // ═══════════════════════════════════════════
    const val POPULARITY_INDEX = 38
    const val ARTIST_FAMILIARITY_INDEX = 39

    /** Total feature dimension for MVP */
    const val DIMENSION = 40

    /**
     * Build a song vector from metadata.
     *
     * @param genres list of genre names (e.g. ["Pop", "R&B"])
     * @param moods list of mood tags (e.g. ["Upbeat", "Energetic"])
     * @param bpm estimated BPM (0 if unknown)
     * @param language primary language
     * @param releaseYear release year (null if unknown)
     * @param popularityPercentile [0..1] global popularity
     */
    fun buildSongVector(
        genres: List<String>,
        moods: List<String> = emptyList(),
        bpm: Int = 0,
        language: String? = null,
        releaseYear: Int? = null,
        popularityPercentile: Float = 0f
    ): com.sonicmusic.app.domain.model.recommendation.SongVector {
        val values = FloatArray(DIMENSION)

        // Genre soft membership (normalized so genre weights sum to ~1)
        val genreWeight = if (genres.isNotEmpty()) 1f / genres.size else 0f
        for (genre in genres) {
            val idx = GENRE_INDEX[genre.lowercase().trim()]
            if (idx != null) {
                values[GENRE_OFFSET + idx] = genreWeight
            }
        }

        // Mood tags
        val moodWeight = if (moods.isNotEmpty()) 1f / moods.size else 0f
        for (mood in moods) {
            val idx = MOOD_INDEX[mood.lowercase().trim()]
            if (idx != null) {
                values[MOOD_OFFSET + idx] = moodWeight
            }
        }

        // Tempo bucket (one-hot)
        if (bpm > 0) {
            val bucket = when {
                bpm < 80 -> 0
                bpm < 100 -> 1
                bpm < 120 -> 2
                bpm < 140 -> 3
                else -> 4
            }
            values[TEMPO_OFFSET + bucket] = 1f
        }

        // Language (one-hot)
        if (language != null) {
            val idx = LANG_INDEX[language.lowercase().trim()]
            if (idx != null) {
                values[LANG_OFFSET + idx] = 1f
            }
        }

        // Era/decade (one-hot)
        if (releaseYear != null) {
            val bucket = when {
                releaseYear < 2000 -> 0
                releaseYear < 2010 -> 1
                releaseYear < 2020 -> 2
                releaseYear < 2025 -> 3
                else -> 4
            }
            values[ERA_OFFSET + bucket] = 1f
        }

        // Popularity percentile
        values[POPULARITY_INDEX] = popularityPercentile.coerceIn(0f, 1f)

        // Artist familiarity left at 0 here — set per-user at scoring time
        // values[ARTIST_FAMILIARITY_INDEX] = 0f

        return com.sonicmusic.app.domain.model.recommendation.SongVector(values).normalized()
    }

    /**
     * Build a song vector from artist name using the shared genre map,
     * for songs where we only have artist metadata.
     */
    fun buildFromArtist(
        artistName: String,
        releaseYear: Int? = null,
        popularityPercentile: Float = 0f,
        language: String? = null
    ): com.sonicmusic.app.domain.model.recommendation.SongVector {
        val genres = inferGenresFromArtist(artistName)
        return buildSongVector(
            genres = genres,
            language = language,
            releaseYear = releaseYear,
            popularityPercentile = popularityPercentile
        )
    }

    /**
     * Infer genres from artist name using the shared ArtistGenreMap.
     */
    fun inferGenresFromArtist(artistName: String): List<String> {
        val lower = artistName.lowercase().trim()
        val matched = com.sonicmusic.app.features.artists.data.ArtistGenreMap.MAP.entries
            .filter { (key, _) -> key.lowercase().trim() == lower || lower.contains(key.lowercase().trim()) }
            .flatMap { it.value }
            .map { it.trim() }
        return matched.ifEmpty { listOf("Pop") }
    }
}
