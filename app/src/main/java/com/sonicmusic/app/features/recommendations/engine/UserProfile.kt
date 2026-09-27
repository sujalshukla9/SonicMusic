package com.sonicmusic.app.features.recommendations.engine

import com.sonicmusic.app.domain.model.recommendation.SongVector
import com.sonicmusic.app.domain.model.recommendation.UserEvent
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * User profile vector manager.
 *
 * Maintains TWO vectors per user (design doc Section 3.3-3.5, Section 15):
 *   - U_long  (λ=0.01, half-life ~69 days) — durable taste
 *   - U_session (λ=0.3, half-life ~2.3 days) — current mood/session
 *
 * Thread-safe via synchronized blocks.
 */
class UserProfile {

    // ═══════════════════════════════════════════
    // DECAY CONSTANTS
    // ═══════════════════════════════════════════
    companion object {
        /** Long-term decay: half-life ~69 days */
        const val LAMBDA_LONG = 0.01f
        /** Session decay: half-life ~2.3 days */
        const val LAMBDA_SESSION = 0.3f
        /** Milliseconds per day */
        private const val MS_PER_DAY = 86_400_000f

        /** Alpha base for long-term update rule (Section 15) */
        private const val ALPHA_BASE = 0.5f
        /** Interactions needed before profile fully stabilizes */
        private const val N_STABLE = 500f

        /** Half-life formula: ln(2) / λ */
        fun halfLifeDays(lambda: Float): Float = (ln(2f) / lambda)
    }

    // Current vectors (initially null until first event)
    @Volatile
    private var longTermVector: SongVector? = null
    @Volatile
    private var sessionVector: SongVector? = null
    @Volatile
    private var sessionLength: Int = 0
    @Volatile
    private var totalInteractions: Int = 0

    /**
     * Exponential time decay.
     * @param elapsedMs milliseconds since the interaction
     * @param lambda decay rate
     * @return decay multiplier in (0, 1]
     */
    private fun decay(elapsedMs: Long, lambda: Float): Float {
        val days = elapsedMs / MS_PER_DAY
        return exp(-lambda * days)
    }

    /**
     * Process a user event — update both vectors.
     *
     * @param event the user behavior event
     * @param songVector the song's feature vector
     */
    @Synchronized
    fun processEvent(event: UserEvent, songVector: SongVector) {
        val contribution = event.signalType.rawValue * event.signalType.weight
        val weightedSong = songVector * contribution

        // ─── Session vector update (immediate) ───
        val currentSession = sessionVector ?: SongVector.zero(songVector.dimension)
        sessionVector = (currentSession + weightedSong).normalized()
        sessionLength++

        // ─── Long-term vector update (small nudge, full update at end-of-session) ───
        val currentLong = longTermVector ?: SongVector.zero(songVector.dimension)
        val nudgeFactor = 0.02f  // Small immediate nudge (Section 18, step 3)
        longTermVector = (currentLong * (1f - nudgeFactor) + weightedSong * nudgeFactor).normalized()

        totalInteractions++
    }

    /**
     * End-of-session update: merge session summary into long-term profile.
     * Design doc Section 15:
     *   U_long(t) = α · U_long(t-1) + (1 - α) · U_session_summary(t)
     */
    @Synchronized
    fun endSession() {
        val session = sessionVector ?: return
        val longTerm = longTermVector ?: session

        val alpha = dynamicAlpha()
        longTermVector = (longTerm * alpha + session * (1f - alpha)).normalized()

        // Reset session
        sessionVector = null
        sessionLength = 0
    }

    /**
     * Dynamic alpha — new users update fast, established users resist change.
     * α = α_base + (1 - α_base) · min(1, total_interactions / N_stable)
     */
    private fun dynamicAlpha(): Float {
        val maturity = min(1f, totalInteractions / N_STABLE)
        return ALPHA_BASE + (1f - ALPHA_BASE) * maturity
    }

    /**
     * Get the blended user vector for scoring (Section 7.2).
     *
     * session_weight shifts based on session length:
     *   session_weight = min(0.7, 0.15 · session_length)
     *   long_term_weight = 1 - session_weight
     */
    fun getBlendedVector(): SongVector? {
        val longTerm = longTermVector ?: return sessionVector
        val session = sessionVector ?: return longTerm

        val sessionWeight = min(0.7f, 0.15f * sessionLength)
        val longTermWeight = 1f - sessionWeight

        return (longTerm * longTermWeight + session * sessionWeight).normalized()
    }

    /** Get the long-term vector alone */
    fun getLongTermVector(): SongVector? = longTermVector

    /** Get the session vector alone */
    fun getSessionVector(): SongVector? = sessionVector

    /** Current session length (number of songs) */
    fun getSessionLength(): Int = sessionLength

    /** Total lifetime interactions */
    fun getTotalInteractions(): Int = totalInteractions

    /**
     * Seed the long-term vector from onboarding preferences (Section 14).
     * Used for cold-start users who select favorite genres/artists.
     */
    @Synchronized
    fun seedFromOnboarding(seedVector: SongVector) {
        longTermVector = seedVector.normalized()
    }

    /**
     * Restore profile state (e.g. from persistence).
     */
    @Synchronized
    fun restore(longTerm: SongVector?, interactions: Int) {
        longTermVector = longTerm
        totalInteractions = interactions
    }
}
