package com.sonicmusic.app.data.repository

import android.util.Log
import com.sonicmusic.app.data.local.dao.PlaybackHistoryDao
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.ListeningPattern
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.UserTasteProfile
import com.sonicmusic.app.domain.repository.UserTasteRepository
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserTasteRepositoryImpl @Inject constructor(
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val youTubeiService: YouTubeiService
) : UserTasteRepository {

    companion object {
        private const val TAG = "UserTasteRepo"
    }

    override suspend fun getUserTasteProfile(): UserTasteProfile = withContext(Dispatchers.IO) {
        try {
            val topArtists = getTopArtists()
            val listeningPattern = analyzeListeningPattern()
            val completionRate = calculateCompletionRate()
            val avgDuration = playbackHistoryDao.getAveragePlayDuration() ?: 0L
            val searchQueries = buildSearchQueries(topArtists)
            
            UserTasteProfile(
                topArtists = topArtists,
                preferredLanguages = inferLanguagePreferences(topArtists),
                listeningPattern = listeningPattern,
                completionRate = completionRate,
                avgSessionDuration = avgDuration,
                topSearchQueries = searchQueries
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error building taste profile", e)
            UserTasteProfile.DEFAULT
        }
    }

    override suspend fun updateTasteFromPlayback(
        song: Song,
        playDuration: Int,
        completed: Boolean
    ) {
        // Taste is auto-updated via PlaybackHistoryDao
        // This method can be used for real-time preference updates if needed
        Log.d(TAG, "Playback recorded: ${song.title} by ${song.artist}, completed=$completed")
    }

    override suspend fun getPersonalizedSearchQueries(): List<String> = withContext(Dispatchers.IO) {
        val topArtists = getTopArtists()
        buildSearchQueries(topArtists)
    }

    override suspend fun getPersonalizedMix(limit: Int): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val queries = getPersonalizedSearchQueries()
            
            if (queries.isEmpty()) {
                // Fallback for new users
                return@withContext youTubeiService.searchSongs("trending music 2024", limit)
            }
            
            val allSongs = mutableListOf<Song>()
            val songsPerQuery = (limit / queries.size).coerceAtLeast(5)
            
            for (query in queries.take(4)) {
                Log.d(TAG, "Fetching personalized: $query")
                youTubeiService.searchSongs(query, songsPerQuery)
                    .onSuccess { songs -> allSongs.addAll(songs) }
            }
            
            // Shuffle and deduplicate
            val uniqueSongs = allSongs.distinctBy { it.id }.shuffled().take(limit)
            
            Log.d(TAG, "Personalized mix: ${uniqueSongs.size} songs")
            Result.success(uniqueSongs)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting personalized mix", e)
            Result.failure(e)
        }
    }

    override suspend fun getQueueRecommendations(
        currentSong: Song,
        queueSize: Int
    ): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val topArtists = getTopArtists()
            
            // Build query based on current song and user taste
            val query = when {
                topArtists.contains(currentSong.artist) -> {
                    // User likes this artist, get more from them
                    "${currentSong.artist} songs"
                }
                topArtists.isNotEmpty() -> {
                    // Mix current artist with user's favorites
                    listOf(
                        "${currentSong.artist} similar songs",
                        "songs like ${currentSong.title}",
                        "${topArtists.first()} best songs"
                    ).random()
                }
                else -> {
                    // New user - get similar to current song
                    "songs like ${currentSong.title} ${currentSong.artist}"
                }
            }
            
            Log.d(TAG, "Queue recommendations query: $query")
            
            val result = youTubeiService.searchSongs(query, queueSize + 5)
                .map { songs ->
                    // Filter out current song if present
                    songs.filter { it.id != currentSong.id }.take(queueSize)
                }
            
            Log.d(TAG, "Queue recommendations: ${result.getOrNull()?.size ?: 0} songs")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error getting queue recommendations", e)
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PRIVATE HELPER METHODS
    // ═══════════════════════════════════════════════════════════════
    
    private suspend fun getTopArtists(): List<String> {
        return try {
            playbackHistoryDao.getTopArtistsByPlayCount(10)
                .map { it.artist }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun analyzeListeningPattern(): ListeningPattern {
        return try {
            val hourlyData = playbackHistoryDao.getPlaybackByHour()
            if (hourlyData.isEmpty()) return ListeningPattern.MIXED
            
            val peakHour = hourlyData.maxByOrNull { it.count }?.hour ?: 12
            
            when (peakHour) {
                in 5..11 -> ListeningPattern.MORNING_LISTENER
                in 12..16 -> ListeningPattern.AFTERNOON_LISTENER
                in 17..20 -> ListeningPattern.EVENING_LISTENER
                in 21..23, in 0..4 -> ListeningPattern.NIGHT_LISTENER
                else -> ListeningPattern.MIXED
            }
        } catch (e: Exception) {
            ListeningPattern.MIXED
        }
    }

    private suspend fun calculateCompletionRate(): Float {
        return try {
            val stats = playbackHistoryDao.getCompletionStats()
            if (stats.totalCount == 0) return 0.5f
            stats.completedCount.toFloat() / stats.totalCount.toFloat()
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun inferLanguagePreferences(topArtists: List<String>): List<String> {
        // Simple heuristic based on artist names
        val hindiArtists = setOf(
            "Arijit Singh", "Shreya Ghoshal", "Atif Aslam", "Pritam", 
            "A.R. Rahman", "Neha Kakkar", "Badshah", "Honey Singh",
            "Jubin Nautiyal", "B Praak", "Vishal Mishra"
        )
        
        val hindiCount = topArtists.count { artist ->
            hindiArtists.any { it.lowercase() in artist.lowercase() }
        }
        
        return if (hindiCount > topArtists.size / 2) {
            listOf("Hindi", "English", "Punjabi")
        } else {
            listOf("English", "Hindi")
        }
    }

    private fun buildSearchQueries(topArtists: List<String>): List<String> {
        val queries = mutableListOf<String>()
        
        // Artist-based queries
        topArtists.take(3).forEach { artist ->
            queries.add("$artist best songs")
        }
        
        // Time-based queries
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        queries.add(
            when (hour) {
                in 5..11 -> "morning motivational songs"
                in 12..16 -> "afternoon chill music"
                in 17..20 -> "evening relaxing songs"
                else -> "night peaceful music"
            }
        )
        
        // Trending/discovery
        queries.add("trending songs 2024")
        
        return queries
    }
}
