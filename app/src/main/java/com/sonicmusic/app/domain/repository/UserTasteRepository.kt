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
     * Get personalized songs to auto-add to queue when current song ends
     */
    suspend fun getQueueRecommendations(
        currentSong: Song,
        queueSize: Int = 5
    ): Result<List<Song>>
}
