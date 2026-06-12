package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.UserTasteProfile

/**
 * Repository for analyzing user taste and generating personalized recommendations
 */
interface UserTasteRepository {
    
    /**
     * Get the current user's taste profile based on listening history
     */
    suspend fun getUserTasteProfile(): UserTasteProfile
    
    /**
     * Update taste profile after a playback event
     */
    suspend fun updateTasteFromPlayback(
        song: Song,
        playDuration: Int,
        completed: Boolean
    )
    
    /**
     * Get personalized search queries based on user's taste
     */
    suspend fun getPersonalizedSearchQueries(): List<String>
    
    /**
     * Get personalized song recommendations for the home page
     */
    suspend fun getPersonalizedMix(limit: Int = 20): Result<List<Song>>
    
    /**
     * Get queue recommendations based on current song and user taste
     */
    suspend fun getQueueRecommendations(
        currentSong: Song,
        queueSize: Int = 10
    ): Result<List<Song>>

    /**
     * Record a skip event (user played < 30s and moved on)
     */
    suspend fun recordSkip(song: Song)

    /**
     * Get inferred top genres from listening history
     */
    suspend fun getTopGenres(limit: Int = 5): List<String>
}
