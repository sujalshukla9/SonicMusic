package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.Song

/**
 * Provides ranked "Listen Again" songs using the composite scoring algorithm.
 *
 * The implementation aggregates playback history, scores each candidate by
 * recency, frequency, completion rate, context boost, skip penalty, and
 * temporal affinity, then filters and deduplicates.
 */
interface ListenAgainRepository {
    suspend fun getListenAgainSongs(limit: Int = 30): List<Song>
}
