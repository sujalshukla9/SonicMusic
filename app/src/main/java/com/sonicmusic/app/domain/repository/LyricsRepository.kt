package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.LyricsResult

import com.sonicmusic.app.domain.model.Song

/**
 * Repository for fetching song lyrics.
 */
interface LyricsRepository {
    /**
     * Fetch lyrics for the given song.
     * Implementations should cache results in-memory using the song ID.
     */
    suspend fun getLyrics(song: Song): LyricsResult
}
