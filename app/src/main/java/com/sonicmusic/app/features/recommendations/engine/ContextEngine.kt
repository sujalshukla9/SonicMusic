package com.sonicmusic.app.features.recommendations.engine

import com.sonicmusic.app.domain.model.recommendation.SongVector
import java.util.Calendar

/**
 * Context-aware recommendation component.
 * Design doc Section 16.
 *
 * Builds a context vector from current time-of-day, day type, and device,
 * then computes ContextScore = cos_sim(ContextVector, SongVector).
 */
object ContextEngine {

    // ═══════════════════════════════════════════
    // TIME BUCKETS
    // ═══════════════════════════════════════════
    enum class TimeBucket {
        EARLY_MORNING,  // 5-8 AM
        MORNING,        // 8 AM-12 PM
        AFTERNOON,      // 12-5 PM
        EVENING,        // 5-9 PM
        LATE_NIGHT      // 9 PM-5 AM
    }

    enum class DayType { WEEKDAY, WEEKEND }

    /**
     * Get the current context vector based on time and day.
     *
     * Strategy: each time bucket maps to a soft "mood preference" vector
     * built from global listening patterns. This is the global prior;
     * personalized context vectors would be built per-user from their
     * own time-bucketed history (fall back to global when sparse).
     */
    fun getCurrentContextVector(): SongVector {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        return getContextVector(hour, dayOfWeek)
    }

    fun getContextVector(hour: Int, dayOfWeek: Int = Calendar.WEDNESDAY): SongVector {
        val timeBucket = getTimeBucket(hour)
        val dayType = if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
            DayType.WEEKEND
        } else {
            DayType.WEEKDAY
        }

        return buildContextVector(timeBucket, dayType)
    }

    fun getTimeBucket(hour: Int): TimeBucket = when (hour) {
        in 5..7 -> TimeBucket.EARLY_MORNING
        in 8..11 -> TimeBucket.MORNING
        in 12..16 -> TimeBucket.AFTERNOON
        in 17..20 -> TimeBucket.EVENING
        else -> TimeBucket.LATE_NIGHT
    }

    /**
     * Build a context vector that encodes mood/genre preferences for a time bucket.
     * Uses the shared FeatureSpace dimensions.
     *
     * These are hand-tuned global priors based on common listening patterns:
     * - Early morning: calm, acoustic, low-energy
     * - Morning: upbeat, energetic, pop
     * - Afternoon: mixed, moderate energy
     * - Evening: chill, romantic, R&B
     * - Late night: emotional, sad, indie, lo-fi
     * Weekend adds slight energy boost.
     */
    private fun buildContextVector(timeBucket: TimeBucket, dayType: DayType): SongVector {
        val values = FloatArray(FeatureSpace.DIMENSION)

        when (timeBucket) {
            TimeBucket.EARLY_MORNING -> {
                // Calm, acoustic, classical
                values[FeatureSpace.GENRE_OFFSET + FeatureSpace.GENRES.indexOf("Acoustic")] = 0.4f
                values[FeatureSpace.GENRE_OFFSET + FeatureSpace.GENRES.indexOf("Classical")] = 0.3f
                values[FeatureSpace.MOOD_OFFSET + FeatureSpace.MOODS.indexOf("Chill")] = 0.6f
                values[FeatureSpace.TEMPO_OFFSET + 0] = 0.5f  // <80 BPM
                values[FeatureSpace.TEMPO_OFFSET + 1] = 0.3f  // 80-100 BPM
            }
            TimeBucket.MORNING -> {
                // Upbeat, energetic, pop
                values[FeatureSpace.GENRE_OFFSET + FeatureSpace.GENRES.indexOf("Pop")] = 0.4f
                values[FeatureSpace.MOOD_OFFSET + FeatureSpace.MOODS.indexOf("Energetic")] = 0.5f
                values[FeatureSpace.MOOD_OFFSET + FeatureSpace.MOODS.indexOf("Upbeat")] = 0.4f
                values[FeatureSpace.MOOD_OFFSET + FeatureSpace.MOODS.indexOf("Happy")] = 0.3f
                values[FeatureSpace.TEMPO_OFFSET + 2] = 0.3f  // 100-120 BPM
                values[FeatureSpace.TEMPO_OFFSET + 3] = 0.4f  // 120-140 BPM
            }
            TimeBucket.AFTERNOON -> {
                // Mixed, moderate
                values[FeatureSpace.GENRE_OFFSET + FeatureSpace.GENRES.indexOf("Pop")] = 0.3f
                values[FeatureSpace.GENRE_OFFSET + FeatureSpace.GENRES.indexOf("Hip-Hop")] = 0.2f
                values[FeatureSpace.MOOD_OFFSET + FeatureSpace.MOODS.indexOf("Upbeat")] = 0.3f
                values[FeatureSpace.TEMPO_OFFSET + 2] = 0.4f  // 100-120 BPM
            }
            TimeBucket.EVENING -> {
                // Chill, romantic, R&B
                values[FeatureSpace.GENRE_OFFSET + FeatureSpace.GENRES.indexOf("R&B")] = 0.3f
                values[FeatureSpace.GENRE_OFFSET + FeatureSpace.GENRES.indexOf("Bollywood")] = 0.2f
                values[FeatureSpace.MOOD_OFFSET + FeatureSpace.MOODS.indexOf("Chill")] = 0.4f
                values[FeatureSpace.MOOD_OFFSET + FeatureSpace.MOODS.indexOf("Romantic")] = 0.4f
                values[FeatureSpace.TEMPO_OFFSET + 1] = 0.4f  // 80-100 BPM
                values[FeatureSpace.TEMPO_OFFSET + 2] = 0.3f  // 100-120 BPM
            }
            TimeBucket.LATE_NIGHT -> {
                // Emotional, sad, indie, lo-fi
                values[FeatureSpace.GENRE_OFFSET + FeatureSpace.GENRES.indexOf("Indie")] = 0.3f
                values[FeatureSpace.GENRE_OFFSET + FeatureSpace.GENRES.indexOf("Alternative")] = 0.2f
                values[FeatureSpace.MOOD_OFFSET + FeatureSpace.MOODS.indexOf("Sad")] = 0.3f
                values[FeatureSpace.MOOD_OFFSET + FeatureSpace.MOODS.indexOf("Chill")] = 0.3f
                values[FeatureSpace.MOOD_OFFSET + FeatureSpace.MOODS.indexOf("Dark")] = 0.2f
                values[FeatureSpace.TEMPO_OFFSET + 0] = 0.4f  // <80 BPM
                values[FeatureSpace.TEMPO_OFFSET + 1] = 0.3f  // 80-100 BPM
            }
        }

        // Weekend energy boost
        if (dayType == DayType.WEEKEND) {
            values[FeatureSpace.MOOD_OFFSET + FeatureSpace.MOODS.indexOf("Energetic")] += 0.15f
            values[FeatureSpace.GENRE_OFFSET + FeatureSpace.GENRES.indexOf("Dance")] += 0.1f
        }

        return SongVector(values).normalized()
    }
}
