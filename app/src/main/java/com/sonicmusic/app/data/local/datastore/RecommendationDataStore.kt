package com.sonicmusic.app.data.local.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sonicmusic.app.domain.model.recommendation.SongVector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recommendationDataStore: DataStore<Preferences> by preferencesDataStore(name = "recommendation_prefs")

data class SavedUserProfile(
    val vector: SongVector?,
    val totalInteractions: Int
)

data class RecommendationMetrics(
    val totalShown: Long = 0,
    val totalPlayed: Long = 0,
    val totalSkipped: Long = 0,
    val totalLiked: Long = 0,
    val totalDiscovered: Long = 0
) {
    /** Play-rate (CTR) = totalPlayed / totalShown */
    val playRate: Float
        get() = if (totalShown > 0) totalPlayed.toFloat() / totalShown.toFloat() else 0f

    /** Skip-rate = totalSkipped / totalPlayed */
    val skipRate: Float
        get() = if (totalPlayed > 0) totalSkipped.toFloat() / totalPlayed.toFloat() else 0f

    /** Discovery-rate = totalDiscovered / totalPlayed */
    val discoveryRate: Float
        get() = if (totalPlayed > 0) totalDiscovered.toFloat() / totalPlayed.toFloat() else 0f

    /** User satisfaction implicit composite (Design doc Section 20) */
    val implicitSatisfaction: Float
        get() = (0.4f * playRate + 0.3f * (1f - skipRate) + 0.3f * discoveryRate).coerceIn(0f, 1f)
}

/**
 * DataStore persistence for the Recommendation Engine:
 * - Stores long-term user profile vector and interaction counts across sessions
 * - Tracks recommendation health metrics (play rate, skip rate, discovery rate)
 *
 * @see Section 15 & Section 20 of music_recommendation_engine_design.md
 */
@Singleton
class RecommendationDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "RecDataStore"

        private object Keys {
            val LONG_TERM_VECTOR = stringPreferencesKey("long_term_vector")
            val TOTAL_INTERACTIONS = intPreferencesKey("total_interactions")
            val METRIC_SHOWN = longPreferencesKey("metric_shown")
            val METRIC_PLAYED = longPreferencesKey("metric_played")
            val METRIC_SKIPPED = longPreferencesKey("metric_skipped")
            val METRIC_LIKED = longPreferencesKey("metric_liked")
            val METRIC_DISCOVERED = longPreferencesKey("metric_discovered")
        }
    }

    private val safePreferences: Flow<Preferences> = context.recommendationDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.w(TAG, "Error reading recommendation preferences", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    /**
     * Persist the user profile vector and interaction count.
     */
    suspend fun saveProfile(vector: SongVector?, totalInteractions: Int) {
        try {
            context.recommendationDataStore.edit { prefs ->
                if (vector != null) {
                    val encoded = vector.values.joinToString(",") { it.toString() }
                    prefs[Keys.LONG_TERM_VECTOR] = encoded
                }
                prefs[Keys.TOTAL_INTERACTIONS] = totalInteractions
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save recommendation profile", e)
        }
    }

    /**
     * Load the persisted user profile.
     */
    suspend fun loadProfile(): SavedUserProfile {
        return try {
            val prefs = safePreferences.firstOrNull() ?: emptyPreferences()
            val vectorString = prefs[Keys.LONG_TERM_VECTOR]
            val interactions = prefs[Keys.TOTAL_INTERACTIONS] ?: 0

            val vector = if (!vectorString.isNullOrBlank()) {
                val floats = vectorString.split(",")
                    .mapNotNull { it.toFloatOrNull() }
                    .toFloatArray()
                if (floats.isNotEmpty()) SongVector(floats) else null
            } else null

            SavedUserProfile(vector = vector, totalInteractions = interactions)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load recommendation profile", e)
            SavedUserProfile(vector = null, totalInteractions = 0)
        }
    }

    fun getProfileFlow(): Flow<SavedUserProfile> = safePreferences.map { prefs ->
        val vectorString = prefs[Keys.LONG_TERM_VECTOR]
        val interactions = prefs[Keys.TOTAL_INTERACTIONS] ?: 0
        val vector = if (!vectorString.isNullOrBlank()) {
            val floats = vectorString.split(",")
                .mapNotNull { it.toFloatOrNull() }
                .toFloatArray()
            if (floats.isNotEmpty()) SongVector(floats) else null
        } else null

        SavedUserProfile(vector = vector, totalInteractions = interactions)
    }

    // ═══════════════════════════════════════════
    // METRICS TRACKING (Section 20)
    // ═══════════════════════════════════════════

    suspend fun recordShown(count: Int = 1) {
        if (count <= 0) return
        try {
            context.recommendationDataStore.edit { prefs ->
                val current = prefs[Keys.METRIC_SHOWN] ?: 0L
                prefs[Keys.METRIC_SHOWN] = current + count
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record shown metric", e)
        }
    }

    suspend fun recordPlayed() {
        try {
            context.recommendationDataStore.edit { prefs ->
                val current = prefs[Keys.METRIC_PLAYED] ?: 0L
                prefs[Keys.METRIC_PLAYED] = current + 1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record played metric", e)
        }
    }

    suspend fun recordSkipped() {
        try {
            context.recommendationDataStore.edit { prefs ->
                val current = prefs[Keys.METRIC_SKIPPED] ?: 0L
                prefs[Keys.METRIC_SKIPPED] = current + 1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record skipped metric", e)
        }
    }

    suspend fun recordLiked() {
        try {
            context.recommendationDataStore.edit { prefs ->
                val current = prefs[Keys.METRIC_LIKED] ?: 0L
                prefs[Keys.METRIC_LIKED] = current + 1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record liked metric", e)
        }
    }

    suspend fun recordDiscovered() {
        try {
            context.recommendationDataStore.edit { prefs ->
                val current = prefs[Keys.METRIC_DISCOVERED] ?: 0L
                prefs[Keys.METRIC_DISCOVERED] = current + 1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record discovered metric", e)
        }
    }

    fun getMetricsFlow(): Flow<RecommendationMetrics> = safePreferences.map { prefs ->
        RecommendationMetrics(
            totalShown = prefs[Keys.METRIC_SHOWN] ?: 0L,
            totalPlayed = prefs[Keys.METRIC_PLAYED] ?: 0L,
            totalSkipped = prefs[Keys.METRIC_SKIPPED] ?: 0L,
            totalLiked = prefs[Keys.METRIC_LIKED] ?: 0L,
            totalDiscovered = prefs[Keys.METRIC_DISCOVERED] ?: 0L
        )
    }

    suspend fun getMetrics(): RecommendationMetrics {
        val prefs = safePreferences.firstOrNull() ?: emptyPreferences()
        return RecommendationMetrics(
            totalShown = prefs[Keys.METRIC_SHOWN] ?: 0L,
            totalPlayed = prefs[Keys.METRIC_PLAYED] ?: 0L,
            totalSkipped = prefs[Keys.METRIC_SKIPPED] ?: 0L,
            totalLiked = prefs[Keys.METRIC_LIKED] ?: 0L,
            totalDiscovered = prefs[Keys.METRIC_DISCOVERED] ?: 0L
        )
    }
}
