package com.sonicmusic.app.data.repository

import android.util.Log
import com.sonicmusic.app.data.local.dao.ListenAgainRawStats
import com.sonicmusic.app.data.local.dao.PlaybackHistoryDao
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.ListenAgainRepository
import com.sonicmusic.app.domain.usecase.ListenAgainScoringEngine
import com.sonicmusic.app.domain.usecase.ListenAgainScoringEngine.ScoringContext
import com.sonicmusic.app.domain.usecase.ListenAgainScoringEngine.ItemStats
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [ListenAgainRepository].
 *
 * Pipeline:
 *  1. Query raw aggregated stats from [PlaybackHistoryDao.getListenAgainRawStats]
 *  2. Parse time/day distributions from pipe-separated labels
 *  3. Filter by [ListenAgainScoringEngine.isEligible]
 *  4. Score each item via [ListenAgainScoringEngine.computeScore]
 *  5. Sort descending by score
 *  6. Deduplicate (max 2 per artist)
 *  7. Return top N as [Song] objects
 */
@Singleton
class ListenAgainRepositoryImpl @Inject constructor(
    private val historyDao: PlaybackHistoryDao
) : ListenAgainRepository {

    companion object {
        private const val TAG = "ListenAgain"
        private const val MAX_PER_ARTIST = 2
        private const val DAY_MS = 86_400_000L
    }

    override suspend fun getListenAgainSongs(limit: Int): List<Song> {
        val startMs = System.currentTimeMillis()
        return try {
            val now = System.currentTimeMillis()
            val since90d = now - 90 * DAY_MS
            val since30d = now - 30 * DAY_MS
            val since7d  = now - 7 * DAY_MS

            // 1. Query raw stats (with timezone-aware time-of-day/day-of-week)
            val utcOffsetMs = java.util.TimeZone.getDefault().getOffset(now).toLong()
            val rawStats = historyDao.getListenAgainRawStats(since90d, since30d, since7d, utcOffsetMs)

            if (rawStats.isEmpty()) {
                Log.d(TAG, "No qualified listens found")
                return emptyList()
            }

            // 2. Build scoring context
            val cal = Calendar.getInstance()
            val context = ScoringContext(
                currentTimeOfDay = ListenAgainScoringEngine.currentTimeOfDay(cal.get(Calendar.HOUR_OF_DAY)),
                currentDay = ListenAgainScoringEngine.currentDayOfWeek(cal.get(Calendar.DAY_OF_WEEK)),
                nowMillis = now
            )

            // 3. Score, filter, sort
            val scored = rawStats
                .map { raw -> raw to toItemStats(raw) }
                .filter { (_, stats) -> ListenAgainScoringEngine.isEligible(stats, now) }
                .map { (raw, stats) ->
                    val score = ListenAgainScoringEngine.computeScore(stats, context)
                    Triple(raw, stats, score)
                }
                .sortedByDescending { it.third }

            // 4. Deduplicate: max 2 per artist
            val artistCounts = mutableMapOf<String, Int>()
            val result = mutableListOf<Song>()

            for ((raw, _, _) in scored) {
                val artistKey = raw.artist.lowercase().trim()
                val count = artistCounts.getOrDefault(artistKey, 0)
                if (count >= MAX_PER_ARTIST) continue

                artistCounts[artistKey] = count + 1
                result.add(
                    Song(
                        id = raw.songId,
                        title = raw.title,
                        artist = raw.artist,
                        duration = 0,
                        thumbnailUrl = raw.thumbnailUrl
                    )
                )

                if (result.size >= limit) break
            }

            Log.d(TAG, "⏱️ Listen Again scored ${rawStats.size} → ${result.size} items in ${System.currentTimeMillis() - startMs}ms")
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ Listen Again scoring failed", e)
            emptyList()
        }
    }

    // ══════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════

    private fun toItemStats(raw: ListenAgainRawStats): ItemStats {
        return ItemStats(
            lastPlayedAtMillis = raw.lastPlayedAt,
            playCount90d = raw.playCount90d,
            completedCount = raw.completedCount,
            totalPlays = raw.totalPlays,
            skipCount30d = raw.skipCount30d,
            playCount30d = raw.playCount30d,
            playCount7d = raw.playCount7d,
            playCount7dPrior = raw.playCount7dPrior,
            qualifiedListenCount = raw.qualifiedListenCount,
            timeDistribution = parseDistribution(raw.timeOfDayRaw),
            dayDistribution = parseDistribution(raw.dayOfWeekRaw)
        )
    }

    /**
     * Parses a pipe-separated list of labels (e.g. "morning|morning|evening|night")
     * into a frequency map (e.g. {"morning": 2, "evening": 1, "night": 1}).
     */
    private fun parseDistribution(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val freq = mutableMapOf<String, Int>()
        raw.split('|').forEach { label ->
            val trimmed = label.trim()
            if (trimmed.isNotEmpty()) {
                freq[trimmed] = (freq[trimmed] ?: 0) + 1
            }
        }
        return freq
    }
}
