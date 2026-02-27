package com.sonicmusic.app.domain.usecase

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Pure scoring functions for the "Listen Again" ranking algorithm.
 *
 * Composite score:
 *   SCORE = W1·Recency + W2·Frequency + W3·Completion + W4·Context − W5·Skip + W6·Temporal
 *
 * All individual factors return a value in [0, 1].
 */
object ListenAgainScoringEngine {

    // ── Weight defaults (from PRD §3.2) ──
    private const val W1_RECENCY    = 0.35
    private const val W2_FREQUENCY  = 0.25
    private const val W3_COMPLETION = 0.15
    private const val W4_CONTEXT    = 0.10
    private const val W5_SKIP       = 0.10
    private const val W6_TEMPORAL   = 0.05

    // ── Half-life for recency decay ──
    private const val HALF_LIFE_HOURS = 7.0 * 24.0  // 7 days
    private const val LN2 = 0.693147

    // ── Frequency log base (caps at ~50 plays) ──
    private val LOG_BASE = ln(51.0)

    // ══════════════════════════════════════════
    // FACTOR 1: RECENCY (0..1)
    // ══════════════════════════════════════════
    /**
     * Exponential decay – recent plays score highest.
     * Half-life = 7 days (score halves every 7 days).
     */
    fun recencyScore(lastPlayedAtMillis: Long, nowMillis: Long): Double {
        val hoursSince = (nowMillis - lastPlayedAtMillis).toDouble() / 3_600_000.0
        if (hoursSince <= 0) return 1.0
        val score = exp(-LN2 * hoursSince / HALF_LIFE_HOURS)
        return score.coerceIn(0.0, 1.0)
    }

    // ══════════════════════════════════════════
    // FACTOR 2: FREQUENCY (0..1)
    // ══════════════════════════════════════════
    /**
     * Logarithmic scale so 100 plays isn't 100× better than 1.
     * Caps at ~1.0 around 50+ plays.
     */
    fun frequencyScore(playCount90d: Int): Double {
        if (playCount90d <= 0) return 0.0
        return min(1.0, ln(1.0 + playCount90d) / LOG_BASE)
    }

    // ══════════════════════════════════════════
    // FACTOR 3: COMPLETION RATE (0..1)
    // ══════════════════════════════════════════
    fun completionScore(completedCount: Int, totalPlays: Int): Double {
        if (totalPlays <= 0) return 0.0
        return completedCount.toDouble() / totalPlays.toDouble()
    }

    // ══════════════════════════════════════════
    // FACTOR 4: CONTEXT BOOST (0..1)
    // ══════════════════════════════════════════
    /**
     * Boost if user historically plays this track at the current time of day.
     */
    fun contextBoost(timeDistribution: Map<String, Int>, currentTimeOfDay: String): Double {
        val total = timeDistribution.values.sum()
        if (total <= 0) return 0.0
        return (timeDistribution[currentTimeOfDay] ?: 0).toDouble() / total.toDouble()
    }

    // ══════════════════════════════════════════
    // FACTOR 5: SKIP PENALTY (0..1)
    // ══════════════════════════════════════════
    fun skipPenalty(skipCount30d: Int, playCount30d: Int): Double {
        if (playCount30d <= 0) return 0.0
        return min(1.0, skipCount30d.toDouble() / playCount30d.toDouble())
    }

    // ══════════════════════════════════════════
    // FACTOR 6: TEMPORAL AFFINITY (0..1)
    // ══════════════════════════════════════════
    /**
     * Boost if user historically plays this track on the current day of week.
     */
    fun temporalAffinity(dayDistribution: Map<String, Int>, currentDay: String): Double {
        val total = dayDistribution.values.sum()
        if (total <= 0) return 0.0
        return (dayDistribution[currentDay] ?: 0).toDouble() / total.toDouble()
    }

    // ══════════════════════════════════════════
    // COMPOSITE SCORE
    // ══════════════════════════════════════════
    data class ScoringContext(
        val currentTimeOfDay: String,   // "morning" | "afternoon" | "evening" | "night"
        val currentDay: String,         // "mon" .. "sun"
        val nowMillis: Long
    )

    data class ItemStats(
        val lastPlayedAtMillis: Long,
        val playCount90d: Int,
        val completedCount: Int,
        val totalPlays: Int,
        val skipCount30d: Int,
        val playCount30d: Int,
        val playCount7d: Int,
        val playCount7dPrior: Int,
        val qualifiedListenCount: Int,
        val timeDistribution: Map<String, Int>,
        val dayDistribution: Map<String, Int>
    )

    fun computeScore(stats: ItemStats, context: ScoringContext): Double {
        val r   = recencyScore(stats.lastPlayedAtMillis, context.nowMillis)
        val f   = frequencyScore(stats.playCount90d)
        val c   = completionScore(stats.completedCount, stats.totalPlays)
        val ctx = contextBoost(stats.timeDistribution, context.currentTimeOfDay)
        val s   = skipPenalty(stats.skipCount30d, stats.playCount30d)
        val t   = temporalAffinity(stats.dayDistribution, context.currentDay)

        val score = (W1_RECENCY * r) +
                    (W2_FREQUENCY * f) +
                    (W3_COMPLETION * c) +
                    (W4_CONTEXT * ctx) -
                    (W5_SKIP * s) +
                    (W6_TEMPORAL * t)

        return max(0.0, score)
    }

    // ══════════════════════════════════════════
    // ELIGIBILITY CHECK
    // ══════════════════════════════════════════
    /**
     * Determines whether an item should appear in "Listen Again".
     *
     * Rules (from PRD §5):
     * - Must have ≥1 qualified listen
     * - Drop off after 90 days no listen (already filtered by SQL)
     * - Burnout: if >15 plays in prior week AND 0 plays this week → suppress 14 days
     */
    fun isEligible(stats: ItemStats, nowMillis: Long): Boolean {
        // Must have at least 1 qualified listen
        if (stats.qualifiedListenCount < 1) return false

        val daysSinceLast = (nowMillis - stats.lastPlayedAtMillis) / 86_400_000L

        // Already filtered at 90d by SQL, but double-check
        if (daysSinceLast > 90) return false

        // Burnout detection: >15 plays in prior 7d AND 0 plays this 7d → suppress 14d
        if (stats.playCount7dPrior > 15 && stats.playCount7d == 0 && daysSinceLast < 14) {
            return false
        }

        return true
    }

    // ══════════════════════════════════════════
    // TIME HELPERS
    // ══════════════════════════════════════════
    fun currentTimeOfDay(hourOfDay: Int): String = when {
        hourOfDay < 6  -> "night"
        hourOfDay < 12 -> "morning"
        hourOfDay < 17 -> "afternoon"
        else           -> "evening"
    }

    fun currentDayOfWeek(dayOfWeek: Int): String = when (dayOfWeek) {
        java.util.Calendar.MONDAY    -> "mon"
        java.util.Calendar.TUESDAY   -> "tue"
        java.util.Calendar.WEDNESDAY -> "wed"
        java.util.Calendar.THURSDAY  -> "thu"
        java.util.Calendar.FRIDAY    -> "fri"
        java.util.Calendar.SATURDAY  -> "sat"
        java.util.Calendar.SUNDAY    -> "sun"
        else -> "mon"
    }
}
