package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.Song

/**
 * Provides the Quick Picks section — a personalized mix of familiar tracks
 * (60%) and discovery tracks (40%), scored, interleaved (F-F-D pattern),
 * and session-shuffled.
 */
interface QuickPicksRepository {
    /**
     * Get Quick Picks for the current session.
     * Results are cached for 6 hours per session seed.
     *
     * @param limit target number of songs (default 25)
     * @return list of scored and interleaved songs
     */
    suspend fun getQuickPicks(limit: Int = 25): List<Song>
}
