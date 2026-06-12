package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository for managing playback queue with infinite recommendations
 */
interface QueueRepository {
    
    /**
     * Current queue as a Flow for reactive observation
     */
    val queue: StateFlow<List<Song>>
    
    /**
     * Current index in the queue
     */
    val currentIndex: StateFlow<Int>
    
    /**
     * Whether infinite queue mode is enabled
     */
    val infiniteModeEnabled: StateFlow<Boolean>
    
    /**
     * Get related/similar songs for recommendation
     * Uses the current song to find similar tracks
     */
    suspend fun getRelatedSongs(songId: String, limit: Int = 10): Result<List<Song>>
    
    /**
     * Get mix recommendations based on current song
     */
    suspend fun getSongMix(songId: String, limit: Int = 25): Result<List<Song>>
    
    /**
     * Add songs to the end of the queue
     */
    suspend fun addToQueue(songs: List<Song>)
    
    /**
     * Clear the queue
     */
    suspend fun clearQueue()
    
    /**
     * Set infinite mode (auto-add recommendations)
     */
    suspend fun setInfiniteMode(enabled: Boolean)
    
    /**
     * Should be called when queue is running low to fetch more songs
     * Returns true if more songs were added
     */
    suspend fun ensureQueueNotEmpty(currentSongId: String): Boolean
}
