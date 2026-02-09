package com.sonicmusic.app.data.repository

import android.util.Log
import com.sonicmusic.app.data.local.dao.PlaybackHistoryDao
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.Artist
import com.sonicmusic.app.domain.model.ArtistSection
import com.sonicmusic.app.domain.model.HomeContent
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.RecommendationRepository
import com.sonicmusic.app.domain.repository.UserTasteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Smart Recommendation Repository with User Taste Integration
 * 
 * Logic for each section:
 * - Quick Picks: Uses user's top artists + time-of-day preferences
 * - Listen Again: Retrieved from playback history (handled by HistoryRepository)
 * - For You: Uses getPersonalizedMix from UserTasteRepository
 * - Top Artists: Combines user's top artists with popular artists
 */
@Singleton
class RecommendationRepositoryImpl @Inject constructor(
    private val youTubeiService: YouTubeiService,
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val userTasteRepository: UserTasteRepository
) : RecommendationRepository {

    companion object {
        private const val TAG = "RecommendationRepo"
        
        // Time-based mood queries
        private val MORNING_QUERIES = listOf(
            "morning motivation songs Hindi",
            "good morning Hindi songs",
            "energetic Hindi songs",
            "workout Hindi music"
        )
        
        private val AFTERNOON_QUERIES = listOf(
            "popular Hindi songs 2024",
            "trending Bollywood songs",
            "top Hindi music",
            "chill Hindi songs"
        )
        
        private val EVENING_QUERIES = listOf(
            "romantic Hindi songs",
            "evening mood Hindi songs",
            "relaxing Hindi music",
            "soft Hindi songs"
        )
        
        private val NIGHT_QUERIES = listOf(
            "late night Hindi songs",
            "sad Hindi songs",
            "slow Hindi music",
            "emotional Bollywood songs"
        )
        
        // Default popular artists (fallback when no user history)
        private val DEFAULT_POPULAR_ARTISTS = listOf(
            "Arijit Singh",
            "Shreya Ghoshal",
            "Atif Aslam",
            "Pritam",
            "A.R. Rahman",
            "Neha Kakkar",
            "Jubin Nautiyal",
            "Badshah"
        )
    }

    /**
     * Get Quick Picks - SMART PERSONALIZED RECOMMENDATIONS
     * 
     * Logic:
     * 1. Check if user has playback history
     * 2. If yes: Use top artists + time-of-day preference
     * 3. If no: Use popular trending songs based on time-of-day
     * 4. Mix results for variety
     */
    override suspend fun getQuickPicks(limit: Int): Result<List<Song>> {
        return try {
            Log.d(TAG, "🎯 Getting smart quick picks...")
            
            // Get user's taste profile
            val tasteProfile = userTasteRepository.getUserTasteProfile()
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            
            val allSongs = mutableListOf<Song>()
            
            if (tasteProfile.topArtists.isNotEmpty()) {
                // User has listening history - use personalized queries
                Log.d(TAG, "📊 User has history: ${tasteProfile.topArtists.size} top artists")
                
                // Strategy 1: Search by top artist (50% of results)
                val topArtist = tasteProfile.topArtists.first()
                val artistQuery = "$topArtist best songs"
                Log.d(TAG, "🎤 Searching: $artistQuery")
                
                youTubeiService.searchSongs(artistQuery, limit / 2).onSuccess { songs ->
                    allSongs.addAll(songs)
                }
                
                // Strategy 2: Similar music to user's taste + time context (50% of results)
                val moodQuery = getTimeBasedQuery(currentHour)
                Log.d(TAG, "🕐 Time-based query: $moodQuery")
                
                youTubeiService.searchSongs(moodQuery, limit / 2).onSuccess { songs ->
                    allSongs.addAll(songs)
                }
                
            } else {
                // New user - use trending + time-based mood
                Log.d(TAG, "🆕 New user - using trending recommendations")
                
                // Popular songs based on time of day
                val query = getTimeBasedQuery(currentHour)
                Log.d(TAG, "🕐 Query: $query")
                
                youTubeiService.searchSongs(query, limit).onSuccess { songs ->
                    allSongs.addAll(songs)
                }
            }
            
            // Shuffle and deduplicate
            val uniqueSongs = allSongs
                .distinctBy { it.id }
                .shuffled()
                .take(limit)
            
            Log.d(TAG, "✅ Quick picks ready: ${uniqueSongs.size} songs")
            Result.success(uniqueSongs)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Quick picks error", e)
            Result.failure(e)
        }
    }

    /**
     * Get time-based mood query based on hour of day
     */
    private fun getTimeBasedQuery(hour: Int): String {
        return when (hour) {
            in 5..11 -> MORNING_QUERIES.random()    // 5 AM - 11 AM
            in 12..16 -> AFTERNOON_QUERIES.random() // 12 PM - 4 PM
            in 17..20 -> EVENING_QUERIES.random()   // 5 PM - 8 PM
            else -> NIGHT_QUERIES.random()          // 9 PM - 4 AM
        }
    }

    /**
     * Get Forgotten Favorites - Songs played often but not recently
     * 
     * Logic:
     * 1. Get most played songs (by play count)
     * 2. Filter out songs played in last 14 days
     * 3. Return rediscovery suggestions
     */
    override suspend fun getForgottenFavorites(limit: Int): Result<List<Song>> {
        return try {
            Log.d(TAG, "💭 Getting forgotten favorites...")
            
            // Get songs not played in last 14 days
            val fourteenDaysAgo = System.currentTimeMillis() - (14L * 24 * 60 * 60 * 1000)
            val mostPlayed = playbackHistoryDao.getMostPlayedSince(fourteenDaysAgo)
            
            // Get recently played IDs to exclude
            val recentIds = playbackHistoryDao.getRecentSongIds(20).toSet()
            
            // Filter: songs with high play count but not recently played
            val forgotten = mostPlayed
                .filter { !recentIds.contains(it.songId) }
                .sortedByDescending { it.playDuration } // Sort by play duration (longer = more liked)
                .take(limit)
            
            val songs = forgotten.map { history ->
                Song(
                    id = history.songId,
                    title = history.title,
                    artist = history.artist,
                    duration = history.playDuration,
                    thumbnailUrl = history.thumbnailUrl
                )
            }
            
            Log.d(TAG, "✅ Forgotten favorites: ${songs.size} songs")
            Result.success(songs)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Forgotten favorites error", e)
            Result.failure(e)
        }
    }

    /**
     * Get Top Artist Songs - User's favorite artists + popular artists
     * 
     * Logic:
     * 1. Get user's top played artists from history
     * 2. Mix with popular artists for variety
     * 3. Fetch top songs for each artist
     */
    override suspend fun getTopArtistSongs(limit: Int): Result<List<ArtistSection>> {
        return try {
            Log.d(TAG, "🎤 Getting top artist songs...")
            
            // Get user's top artists from taste profile
            val tasteProfile = userTasteRepository.getUserTasteProfile()
            val userTopArtists = tasteProfile.topArtists.take(3)
            
            // Mix user's artists with popular artists
            val artistsToQuery = if (userTopArtists.isNotEmpty()) {
                // Combine user's top artists with popular ones
                val combined = userTopArtists + DEFAULT_POPULAR_ARTISTS
                    .filter { !userTopArtists.contains(it) }
                    .take(2)
                combined.take(limit)
            } else {
                // New user - use popular artists
                DEFAULT_POPULAR_ARTISTS.shuffled().take(limit)
            }
            
            Log.d(TAG, "📋 Artists to query: $artistsToQuery")
            
            val artistSections = mutableListOf<ArtistSection>()
            
            for (artistName in artistsToQuery) {
                val query = "$artistName top songs"
                Log.d(TAG, "🔍 Searching: $query")
                
                val songs = youTubeiService.searchSongs(query, 8).getOrNull()
                
                if (!songs.isNullOrEmpty()) {
                    artistSections.add(
                        ArtistSection(
                            artist = Artist(
                                id = artistName.lowercase().replace(" ", "_"),
                                name = artistName,
                                songCount = songs.size
                            ),
                            songs = songs
                        )
                    )
                }
                
                // Stop after getting 3 successful sections
                if (artistSections.size >= 3) break
            }
            
            Log.d(TAG, "✅ Got ${artistSections.size} artist sections")
            Result.success(artistSections)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Top artist songs error", e)
            Result.failure(e)
        }
    }

    override fun getHomeContent(): Flow<Result<HomeContent>> = flow {
        emit(Result.success(HomeContent()))
    }
}