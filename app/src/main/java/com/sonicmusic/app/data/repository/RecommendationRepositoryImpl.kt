package com.sonicmusic.app.data.repository

import android.util.Log
import com.sonicmusic.app.data.local.dao.PlaybackHistoryDao
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.Artist
import com.sonicmusic.app.domain.model.ArtistSection
import com.sonicmusic.app.domain.model.HomeContent
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.RecommendationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecommendationRepositoryImpl @Inject constructor(
    private val youTubeiService: YouTubeiService,
    private val playbackHistoryDao: PlaybackHistoryDao
) : RecommendationRepository {

    companion object {
        private const val TAG = "RecommendationRepo"
    }

    override suspend fun getQuickPicks(limit: Int): Result<List<Song>> {
        return try {
            Log.d(TAG, "Getting quick picks...")
            
            // Get user's recent history
            val recentHistory = playbackHistoryDao.getRecentSongIds(50)
            
            // Select search query based on history
            val query = if (recentHistory.isNotEmpty()) {
                // User has history, get personalized recommendations
                listOf(
                    "best songs 2024 India",
                    "top Hindi songs",
                    "popular Indian music",
                    "Bollywood hits"
                ).random()
            } else {
                // Default for new users - popular Indian music
                listOf(
                    "popular songs India 2024",
                    "top Hindi songs",
                    "best Bollywood songs",
                    "trending music India"
                ).random()
            }
            
            Log.d(TAG, "Quick picks query: $query")
            val result = youTubeiService.searchSongs(query, limit)
            Log.d(TAG, "Quick picks result: ${result.getOrNull()?.size ?: 0} songs")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Quick picks error", e)
            Result.failure(e)
        }
    }

    override suspend fun getForgottenFavorites(limit: Int): Result<List<Song>> {
        return try {
            // Get songs played 5+ times but not in last 30 days
            val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
            val mostPlayed = playbackHistoryDao.getMostPlayedSince(thirtyDaysAgo)
            
            // Filter out recently played
            val recentIds = playbackHistoryDao.getRecentSongIds(30).toSet()
            val forgotten = mostPlayed
                .filter { !recentIds.contains(it.songId) }
                .take(limit)
            
            // Convert to songs
            val songs = forgotten.map { history ->
                Song(
                    id = history.songId,
                    title = history.title,
                    artist = history.artist,
                    duration = 0,
                    thumbnailUrl = history.thumbnailUrl
                )
            }
            
            Result.success(songs)
        } catch (e: Exception) {
            Log.e(TAG, "Forgotten favorites error", e)
            Result.failure(e)
        }
    }

    override suspend fun getTopArtistSongs(limit: Int): Result<List<ArtistSection>> {
        return try {
            Log.d(TAG, "Getting top artist songs...")
            
            // Popular Indian artists for better content
            val artists = listOf(
                Pair("Arijit Singh", "arijit_singh"),
                Pair("Shreya Ghoshal", "shreya_ghoshal"),
                Pair("Atif Aslam", "atif_aslam"),
                Pair("Pritam", "pritam"),
                Pair("A.R. Rahman", "ar_rahman")
            )
            
            val artistSections = mutableListOf<ArtistSection>()
            
            for ((artistName, artistId) in artists.take(limit)) {
                val query = "$artistName top songs"
                Log.d(TAG, "Searching: $query")
                
                val songs = youTubeiService.searchSongs(query, 8).getOrNull()
                
                if (!songs.isNullOrEmpty()) {
                    artistSections.add(
                        ArtistSection(
                            artist = Artist(
                                id = artistId,
                                name = artistName,
                                songCount = songs.size
                            ),
                            songs = songs
                        )
                    )
                }
                
                // Stop after getting 3 successful artist sections
                if (artistSections.size >= 3) break
            }
            
            Log.d(TAG, "Got ${artistSections.size} artist sections")
            Result.success(artistSections)
        } catch (e: Exception) {
            Log.e(TAG, "Top artist songs error", e)
            Result.failure(e)
        }
    }

    override fun getHomeContent(): Flow<Result<HomeContent>> = flow {
        emit(Result.success(HomeContent()))
    }
}