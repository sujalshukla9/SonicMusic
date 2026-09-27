package com.sonicmusic.app.features.recommendations.engine

import android.util.Log
import com.sonicmusic.app.data.local.dao.PlaybackHistoryDao
import com.sonicmusic.app.data.local.datastore.RecommendationDataStore
import com.sonicmusic.app.data.local.datastore.RecommendationMetrics
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.recommendation.SignalType
import com.sonicmusic.app.domain.model.recommendation.SongVector
import com.sonicmusic.app.domain.model.recommendation.UserEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Main recommendation engine orchestrator.
 *
 * Ties together all MVP components from the design doc Section 24:
 *   1. Signal weights (Section 2)
 *   2. Content-based U/S vectors (Section 3)
 *   3. Scoring formula (Section 5) — without CF term for MVP
 *   4. Cooldown system (Section 12)
 *   5. MMR diversity rerank (Section 13)
 *   6. Context-aware scoring (Section 16)
 *   7. Epsilon-greedy exploration at genre level (Section 8)
 *   8. Cold-start onboarding (Section 14)
 *   9. Persistent user profile via RecommendationDataStore (Section 15)
 *   10. Online recommendation metrics tracking (Section 20)
 */
@Singleton
class RecommendationEngine @Inject constructor(
    private val recommendationDataStore: RecommendationDataStore,
    private val playbackHistoryDao: PlaybackHistoryDao
) {

    companion object {
        private const val TAG = "RecEngine"
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ═══════════════════════════════════════════
    // CORE COMPONENTS
    // ═══════════════════════════════════════════
    val userProfile = UserProfile()
    val cooldownManager = CooldownManager()
    val scoringEngine = ScoringEngine(cooldownManager)

    // Per-artist play counts (refreshed from DB on home load)
    private val artistPlayCounts = mutableMapOf<String, Int>()
    private val genrePlayCounts = mutableMapOf<String, Int>()
    private val dislikedArtists = mutableSetOf<String>()

    init {
        // Asynchronously restore profile and bootstrap historical artist counts
        engineScope.launch {
            try {
                // 1. Restore profile from DataStore
                val saved = recommendationDataStore.loadProfile()
                if (saved.vector != null) {
                    userProfile.restore(saved.vector, saved.totalInteractions)
                    Log.d(TAG, "Restored persistent profile: ${saved.totalInteractions} interactions")
                }

                // 2. Bootstrap artist play counts from database
                val topArtists = playbackHistoryDao.getTopArtistsByPlayCount(100)
                if (topArtists.isNotEmpty()) {
                    val map = topArtists.associate { it.artist.lowercase() to it.playCount }
                    loadArtistPlayCounts(map)

                    val genreCounts = mutableMapOf<String, Int>()
                    topArtists.forEach { artistCount ->
                        val genres = FeatureSpace.inferGenresFromArtist(artistCount.artist)
                        genres.forEach { g ->
                            val key = g.lowercase()
                            genreCounts[key] = (genreCounts[key] ?: 0) + artistCount.playCount
                        }
                    }
                    loadGenrePlayCounts(genreCounts)
                    Log.d(TAG, "Bootstrapped from history: ${topArtists.size} artists, ${genreCounts.size} genres")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error initializing RecommendationEngine from persistence", e)
            }
        }
    }

    // ═══════════════════════════════════════════
    // EVENT PROCESSING (Section 2, 17, 18)
    // ═══════════════════════════════════════════

    /**
     * Process a playback completion event.
     * This is the primary feedback loop entry point.
     */
    fun onPlaybackCompleted(
        song: Song,
        playDurationSec: Int,
        totalDurationSec: Int
    ) {
        val completionPct = if (totalDurationSec > 0) {
            playDurationSec.toFloat() / totalDurationSec.toFloat()
        } else 0f

        val signalType = UserEvent.signalFromCompletion(completionPct, totalDurationSec, playDurationSec)
        val songVector = buildSongVectorFromSong(song)

        val event = UserEvent(
            signalType = signalType,
            songId = song.id,
            artistId = song.artistId,
            artistName = song.artist,
            genre = FeatureSpace.inferGenresFromArtist(song.artist).firstOrNull(),
            completionPct = completionPct
        )

        userProfile.processEvent(event, songVector)

        // Update play counts
        val artistKey = song.artist.lowercase()
        val priorArtistPlays = artistPlayCounts[artistKey] ?: 0
        artistPlayCounts[artistKey] = priorArtistPlays + 1

        val genre = FeatureSpace.inferGenresFromArtist(song.artist).firstOrNull()?.lowercase()
        if (genre != null) {
            genrePlayCounts[genre] = (genrePlayCounts[genre] ?: 0) + 1
        }

        // Record in cooldown manager
        cooldownManager.recordPlayed(song.id, song.artist, song.albumId)

        // If skip, record it
        val isSkip = signalType == SignalType.SKIP_EARLY || signalType == SignalType.SKIP_MID
        if (isSkip) {
            cooldownManager.recordSkip(song.id)
        }

        // Persist profile & update metrics in background
        engineScope.launch {
            recommendationDataStore.saveProfile(
                vector = userProfile.getLongTermVector(),
                totalInteractions = userProfile.getTotalInteractions()
            )
            recommendationDataStore.recordPlayed()
            if (isSkip) {
                recommendationDataStore.recordSkipped()
            }
            if (priorArtistPlays == 0) {
                // Discovery: first time playing this artist!
                recommendationDataStore.recordDiscovered()
            }
        }

        Log.d(TAG, "Event: ${signalType.name} for '${song.title}' " +
                "(completion=${String.format("%.0f%%", completionPct * 100)})")
    }

    /**
     * Process a like event.
     */
    fun onLike(song: Song) {
        val songVector = buildSongVectorFromSong(song)
        val event = UserEvent(
            signalType = SignalType.LIKE,
            songId = song.id,
            artistId = song.artistId,
            artistName = song.artist,
            genre = FeatureSpace.inferGenresFromArtist(song.artist).firstOrNull()
        )
        userProfile.processEvent(event, songVector)

        engineScope.launch {
            recommendationDataStore.saveProfile(
                vector = userProfile.getLongTermVector(),
                totalInteractions = userProfile.getTotalInteractions()
            )
            recommendationDataStore.recordLiked()
        }

        Log.d(TAG, "Like: '${song.title}' by ${song.artist}")
    }

    /**
     * Process a dislike event.
     */
    fun onDislike(song: Song) {
        val songVector = buildSongVectorFromSong(song)
        val event = UserEvent(
            signalType = SignalType.DISLIKE,
            songId = song.id,
            artistId = song.artistId,
            artistName = song.artist,
            genre = FeatureSpace.inferGenresFromArtist(song.artist).firstOrNull()
        )
        userProfile.processEvent(event, songVector)
        dislikedArtists.add(song.artist.lowercase())

        engineScope.launch {
            recommendationDataStore.saveProfile(
                vector = userProfile.getLongTermVector(),
                totalInteractions = userProfile.getTotalInteractions()
            )
        }

        Log.d(TAG, "Dislike: '${song.title}' by ${song.artist}")
    }

    /**
     * End the current listening session.
     * Merges session vector into long-term profile.
     */
    fun endSession() {
        userProfile.endSession()
        engineScope.launch {
            recommendationDataStore.saveProfile(
                vector = userProfile.getLongTermVector(),
                totalInteractions = userProfile.getTotalInteractions()
            )
        }
        Log.d(TAG, "Session ended, profile updated and saved")
    }

    // ═══════════════════════════════════════════
    // SCORING & RANKING (Section 5, 6)
    // ═══════════════════════════════════════════

    /**
     * Score and rank a pool of candidate songs.
     * Full pipeline: score → rank → diversity rerank.
     *
     * @param candidates raw candidate songs from candidate generation
     * @param targetSize number of songs to return
     * @param lambdaDiversity diversity strength for this section
     * @param sectionWeightOverrides optional weight overrides per section (Section 6)
     * @return ranked and diversified list of songs
     */
    fun rankCandidates(
        candidates: List<Song>,
        targetSize: Int = 20,
        lambdaDiversity: Float = DiversityReranker.LAMBDA_DEFAULT,
        sectionWeightOverrides: SectionWeights? = null
    ): List<Song> {
        if (candidates.isEmpty()) return emptyList()

        // Apply section-specific weight overrides
        sectionWeightOverrides?.let { applyWeights(it) }

        val contextVector = ContextEngine.getCurrentContextVector()
        val userVector = userProfile.getBlendedVector()

        // Convert Song → ScoredCandidate
        val scoredCandidates = candidates
            .filter { !cooldownManager.isHardBlocked(it.id) }
            .map { song ->
                ScoredCandidate(
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    artistId = song.artistId,
                    albumId = song.albumId,
                    thumbnailUrl = song.thumbnailUrl,
                    duration = song.duration,
                    songVector = buildSongVectorFromSong(song),
                    genre = FeatureSpace.inferGenresFromArtist(song.artist).firstOrNull(),
                    contentType = song.contentType
                )
            }

        // Score all candidates
        val ranked = scoringEngine.scoreAndRank(
            candidates = scoredCandidates,
            userVector = userVector,
            contextVector = contextVector,
            userInteractionCount = userProfile.getTotalInteractions(),
            artistPlayCounts = artistPlayCounts,
            genrePlayCounts = genrePlayCounts,
            dislikedArtists = dislikedArtists
        )

        // Diversity rerank
        val diversified = DiversityReranker.rerank(
            rankedCandidates = ranked,
            targetSize = targetSize,
            lambdaDiversity = lambdaDiversity
        )

        // Track shown songs for cooldown
        diversified.forEach { candidate ->
            cooldownManager.recordShown(candidate.songId, candidate.artist, candidate.albumId)
            candidate.genre?.let { cooldownManager.trackRecommendedGenre(it) }
        }

        // Record shown metric
        engineScope.launch {
            recommendationDataStore.recordShown(diversified.size)
        }

        // Restore default weights if we overrode them
        sectionWeightOverrides?.let { restoreDefaultWeights() }

        Log.d(TAG, "Ranked ${candidates.size} → ${diversified.size} songs " +
                "(diversity=${String.format("%.2f", DiversityReranker.artistDiversityScore(diversified))})")

        // Convert back to Song
        return diversified.map { it.toSong() }
    }

    // ═══════════════════════════════════════════
    // SECTION-SPECIFIC CONFIGURATIONS (Section 6)
    // ═══════════════════════════════════════════

    /** Weight overrides for different home page sections */
    data class SectionWeights(
        val userAffinity: Float = 3.0f,
        val songAffinity: Float = 1.0f,
        val contextScore: Float = 1.2f,
        val recencyScore: Float = 0.5f,
        val popularityScore: Float = 0.6f,
        val explorationBonus: Float = 0.8f,
        val lambdaDiversity: Float = DiversityReranker.LAMBDA_DEFAULT
    )

    val QUICK_PICKS_WEIGHTS = SectionWeights()  // Balanced defaults

    val LISTEN_AGAIN_WEIGHTS = SectionWeights(
        userAffinity = 3.5f,
        recencyScore = 1.5f,
        popularityScore = 0.3f,
        explorationBonus = 0.0f,
        lambdaDiversity = DiversityReranker.LAMBDA_MILD
    )

    val MADE_FOR_YOU_WEIGHTS = SectionWeights(
        userAffinity = 4.0f,
        popularityScore = 0.3f,
        explorationBonus = 0.5f,
        lambdaDiversity = DiversityReranker.LAMBDA_DEFAULT
    )

    val TRENDING_WEIGHTS = SectionWeights(
        userAffinity = 0.5f,
        popularityScore = 3.0f,
        recencyScore = 2.0f,
        explorationBonus = 0.0f,
        lambdaDiversity = DiversityReranker.LAMBDA_MILD
    )

    val NEW_RELEASES_WEIGHTS = SectionWeights(
        userAffinity = 1.0f,
        recencyScore = 4.0f,
        popularityScore = 0.5f,
        explorationBonus = 0.3f,
        lambdaDiversity = DiversityReranker.LAMBDA_DEFAULT
    )

    val DISCOVER_WEIGHTS = SectionWeights(
        userAffinity = 1.5f,
        explorationBonus = 3.0f,
        popularityScore = 0.3f,
        lambdaDiversity = DiversityReranker.LAMBDA_STRONG
    )

    private val defaultWeightsBackup = SectionWeights()

    private fun applyWeights(weights: SectionWeights) {
        scoringEngine.w1_userAffinity = weights.userAffinity
        scoringEngine.w2_songAffinity = weights.songAffinity
        scoringEngine.w3_contextScore = weights.contextScore
        scoringEngine.w4_recencyScore = weights.recencyScore
        scoringEngine.w5_popularityScore = weights.popularityScore
        scoringEngine.w6_explorationBonus = weights.explorationBonus
    }

    private fun restoreDefaultWeights() {
        applyWeights(defaultWeightsBackup)
    }

    // ═══════════════════════════════════════════
    // COLD START (Section 14)
    // ═══════════════════════════════════════════

    /**
     * Seed the user profile from onboarding genre/artist preferences.
     * Call after the user selects 3-5 favorites during signup.
     */
    fun seedFromOnboarding(favoriteArtists: List<String>, favoriteGenres: List<String>) {
        val genres = favoriteGenres.toMutableList()
        favoriteArtists.forEach { artist ->
            genres.addAll(FeatureSpace.inferGenresFromArtist(artist))
        }

        val seedVector = FeatureSpace.buildSongVector(
            genres = genres.distinct(),
            popularityPercentile = 0.5f  // Start neutral
        )
        userProfile.seedFromOnboarding(seedVector)

        engineScope.launch {
            recommendationDataStore.saveProfile(
                vector = userProfile.getLongTermVector(),
                totalInteractions = userProfile.getTotalInteractions()
            )
        }

        Log.d(TAG, "Profile seeded from onboarding: " +
                "artists=$favoriteArtists, genres=${genres.distinct()}")
    }

    /**
     * Bulk-load artist play counts from database.
     * Call on app startup or home page refresh.
     */
    fun loadArtistPlayCounts(counts: Map<String, Int>) {
        artistPlayCounts.clear()
        artistPlayCounts.putAll(counts.mapKeys { it.key.lowercase() })
    }

    /**
     * Bulk-load genre play counts from database.
     */
    fun loadGenrePlayCounts(counts: Map<String, Int>) {
        genrePlayCounts.clear()
        genrePlayCounts.putAll(counts.mapKeys { it.key.lowercase() })
    }

    /**
     * Set disliked artists (from skip analysis or explicit dislikes).
     */
    fun setDislikedArtists(artists: Set<String>) {
        dislikedArtists.clear()
        dislikedArtists.addAll(artists.map { it.lowercase() })
    }

    // ═══════════════════════════════════════════
    // METRICS ACCESS (Section 20)
    // ═══════════════════════════════════════════

    fun getMetricsFlow(): Flow<RecommendationMetrics> = recommendationDataStore.getMetricsFlow()

    suspend fun getMetrics(): RecommendationMetrics = recommendationDataStore.getMetrics()

    // ═══════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════

    private fun buildSongVectorFromSong(song: Song): SongVector {
        val genres = FeatureSpace.inferGenresFromArtist(song.artist)
        val popularityPercentile = if (song.viewCount != null && song.viewCount > 0) {
            (kotlin.math.ln(1f + song.viewCount) / kotlin.math.ln(1f + 5_000_000f))
                .coerceIn(0f, 1f)
        } else 0f

        return FeatureSpace.buildSongVector(
            genres = genres,
            releaseYear = song.year,
            popularityPercentile = popularityPercentile
        )
    }

    private fun ScoredCandidate.toSong(): Song = Song(
        id = songId,
        title = title,
        artist = artist,
        artistId = artistId,
        albumId = albumId,
        duration = duration,
        thumbnailUrl = thumbnailUrl,
        contentType = contentType
    )

    /**
     * Periodic maintenance — call hourly or on app resume.
     */
    fun maintenance() {
        cooldownManager.pruneOldEntries()
    }
}
