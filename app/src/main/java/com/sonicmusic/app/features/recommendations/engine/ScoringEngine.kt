package com.sonicmusic.app.features.recommendations.engine

import com.sonicmusic.app.domain.model.recommendation.SongVector
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * A candidate song for recommendation scoring.
 */
data class ScoredCandidate(
    val songId: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val albumId: String? = null,
    val thumbnailUrl: String,
    val duration: Int,
    val songVector: SongVector,
    val genre: String? = null,
    /** Which generation sources produced this candidate */
    val sources: Set<CandidateSource> = emptySet(),
    /** The computed recommendation score (filled by the scoring engine) */
    var score: Float = 0f,
    /** Recommendation probability after sigmoid (filled by scorer) */
    var probability: Float = 0f,
    /** Content type for re-conversion to Song */
    val contentType: com.sonicmusic.app.domain.model.ContentType = com.sonicmusic.app.domain.model.ContentType.SONG
)

/** Where a candidate came from (Section 4) */
enum class CandidateSource {
    RECENTLY_PLAYED,
    LIKED_ARTISTS,
    SIMILAR_ARTISTS,
    TRENDING,
    NEW_RELEASES,
    GENRE_POOL,
    EXPLORATION,
    USER_PLAYLISTS,
    INNERTUBE_SEED
}

/**
 * Core scoring engine implementing the ranking formula from Section 5.
 *
 * RecommendationScore(u, s, c) =
 *     w1·UserAffinity + w2·SongAffinity + w3·ContextScore + w4·RecencyScore
 *   + w5·PopularityScore + w6·ExplorationBonus + w7·SourceQualityBonus
 *   - w8·SkipPenalty - w9·RepetitionPenalty - w10·NegativeFeedbackPenalty
 */
class ScoringEngine(
    private val cooldownManager: CooldownManager
) {
    // ═══════════════════════════════════════════
    // WEIGHT CONFIGURATION (Section 5, tunable)
    // ═══════════════════════════════════════════
    var w1_userAffinity: Float = 3.0f
    var w2_songAffinity: Float = 1.0f
    var w3_contextScore: Float = 1.2f
    var w4_recencyScore: Float = 0.5f
    var w5_popularityScore: Float = 0.6f
    var w6_explorationBonus: Float = 0.8f
    var w7_sourceQualityBonus: Float = 1.0f
    var w8_skipPenalty: Float = 2.5f
    var w9_repetitionPenalty: Float = 2.0f
    var w10_negativeFeedbackPenalty: Float = 3.0f

    // Sigmoid calibration params (Section 5, Platt scaling)
    private val sigmoidScale = 0.6f    // a
    private val sigmoidBias = -2.0f    // b

    companion object {
        /** Release recency decay: half-life ~35 days */
        private const val LAMBDA_RELEASE = 0.02f
        private const val MS_PER_DAY = 86_400_000f

        /** Max play count for log-scaling popularity */
        private const val MAX_PLAY_COUNT_ESTIMATE = 5_000_000L

        /** Exploration epsilon base rate */
        private const val EPSILON_BASE = 0.3f

        /** K normalization constant for familiarity */
        private const val FAMILIARITY_K = 20f
    }

    /**
     * Score a single candidate song.
     *
     * @param candidate the candidate to score
     * @param userVector blended user vector (U_long + U_session, Section 7.2)
     * @param contextVector current context vector (time/day/device, Section 16)
     * @param userInteractionCount total user interactions (for exploration epsilon)
     * @param artistPlayCount how many times this user has played this artist
     * @param genrePlayCount how many times this user has played songs in this genre
     * @param daysSinceRelease days since song release (0 = today)
     * @param globalAvgCompletionRate avg completion rate across all users for this song
     * @param globalLikeRate like rate across all users
     * @param globalPlayCount global play count for popularity scoring
     * @param dislikedArtists set of artists the user has explicitly disliked
     */
    fun score(
        candidate: ScoredCandidate,
        userVector: SongVector?,
        contextVector: SongVector? = null,
        userInteractionCount: Int = 0,
        artistPlayCount: Int = 0,
        genrePlayCount: Int = 0,
        daysSinceRelease: Float = 30f,
        globalAvgCompletionRate: Float = 0.5f,
        globalLikeRate: Float = 0.1f,
        globalPlayCount: Long = 0L,
        dislikedArtists: Set<String> = emptySet()
    ): Float {

        // ─── 1. UserAffinity ───
        val userAffinity = if (userVector != null) {
            userVector.cosineSimilarity(candidate.songVector).coerceIn(-1f, 1f)
        } else {
            0f // No user vector yet (cold start)
        }

        // ─── 2. SongAffinity (global quality signal) ───
        val songAffinity = 0.6f * globalAvgCompletionRate + 0.4f * globalLikeRate

        // ─── 3. ContextScore ───
        val contextScore = if (contextVector != null) {
            contextVector.cosineSimilarity(candidate.songVector).coerceIn(-1f, 1f)
        } else {
            0f
        }

        // ─── 4. RecencyScore ───
        val recencyScore = exp(-LAMBDA_RELEASE * daysSinceRelease)

        // ─── 5. PopularityScore (log-scaled) ───
        val popularityScore = if (globalPlayCount > 0) {
            (ln(1f + globalPlayCount) / ln(1f + MAX_PLAY_COUNT_ESTIMATE)).toFloat()
        } else {
            0f
        }

        // ─── 6. ExplorationBonus ───
        val familiarity = min(1f, (artistPlayCount + 0.5f * genrePlayCount) / FAMILIARITY_K)
        val epsilon = EPSILON_BASE * (1f - min(1f, userInteractionCount / 200f))
        val explorationBonus = if (epsilon > 0f) {
            epsilon * (1f - familiarity)
        } else {
            0f
        }

        // ─── 7. SourceQualityBonus ───
        val sourceQualityBonus = min(0.2f, 0.05f * (candidate.sources.size - 1).coerceAtLeast(0))

        // ─── 8. SkipPenalty ───
        val skipPenalty = cooldownManager.calculateSkipPenalty(candidate.songId)

        // ─── 9. RepetitionPenalty ───
        val repetitionPenalty = cooldownManager.calculatePenalty(
            songId = candidate.songId,
            artistName = candidate.artist,
            albumId = candidate.albumId,
            genre = candidate.genre
        )

        // ─── 10. NegativeFeedbackPenalty ───
        val negativeFeedbackPenalty = if (dislikedArtists.contains(candidate.artist.lowercase())) {
            1.0f  // Strong penalty for disliked artists
        } else {
            0f
        }

        // ═══════════════════════════════════════════
        // FINAL SCORE (Section 5 formula)
        // ═══════════════════════════════════════════
        val rawScore = (
            w1_userAffinity * userAffinity
            + w2_songAffinity * songAffinity
            + w3_contextScore * contextScore
            + w4_recencyScore * recencyScore
            + w5_popularityScore * popularityScore
            + w6_explorationBonus * explorationBonus
            + w7_sourceQualityBonus * sourceQualityBonus
            - w8_skipPenalty * skipPenalty
            - w9_repetitionPenalty * repetitionPenalty
            - w10_negativeFeedbackPenalty * negativeFeedbackPenalty
        )

        // Convert to probability via sigmoid (Platt scaling)
        val probability = sigmoid(sigmoidScale * rawScore + sigmoidBias)

        candidate.score = rawScore
        candidate.probability = probability

        return rawScore
    }

    /**
     * Score a batch of candidates and return them sorted by score (descending).
     */
    fun scoreAndRank(
        candidates: List<ScoredCandidate>,
        userVector: SongVector?,
        contextVector: SongVector? = null,
        userInteractionCount: Int = 0,
        artistPlayCounts: Map<String, Int> = emptyMap(),
        genrePlayCounts: Map<String, Int> = emptyMap(),
        dislikedArtists: Set<String> = emptySet()
    ): List<ScoredCandidate> {
        candidates.forEach { candidate ->
            score(
                candidate = candidate,
                userVector = userVector,
                contextVector = contextVector,
                userInteractionCount = userInteractionCount,
                artistPlayCount = artistPlayCounts[candidate.artist.lowercase()] ?: 0,
                genrePlayCount = genrePlayCounts[candidate.genre?.lowercase() ?: ""] ?: 0,
                dislikedArtists = dislikedArtists
            )
        }
        return candidates.sortedByDescending { it.score }
    }

    /** Logistic sigmoid: 1 / (1 + e^(-x)) */
    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    /** Convert raw RecommendationScore to probability [0, 1] */
    fun toProbability(rawScore: Float): Float = sigmoid(sigmoidScale * rawScore + sigmoidBias)
}
