package com.sonicmusic.app.domain.usecase

import com.sonicmusic.app.domain.model.HomeContent
import com.sonicmusic.app.domain.repository.HistoryRepository
import com.sonicmusic.app.domain.repository.RecommendationRepository
import com.sonicmusic.app.domain.repository.SongRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class GetHomeContentUseCase @Inject constructor(
    private val songRepository: SongRepository,
    private val historyRepository: HistoryRepository,
    private val recommendationRepository: RecommendationRepository
) {
    suspend operator fun invoke(): Result<HomeContent> {
        return try {
            coroutineScope {
                val listenAgainDeferred = async { 
                    historyRepository.getRecentlyPlayedSongs(15) 
                }
                val quickPicksDeferred = async { 
                    recommendationRepository.getQuickPicks(20) 
                }
                val newReleasesDeferred = async { 
                    songRepository.getNewReleases(25) 
                }
                val trendingDeferred = async { 
                    songRepository.getTrending(30) 
                }
                val englishHitsDeferred = async { 
                    songRepository.getEnglishHits(25) 
                }
                val artistsDeferred = async { 
                    recommendationRepository.getTopArtistSongs(8) 
                }

                // Wait for all results
                val listenAgain = listenAgainDeferred.await().first()
                val quickPicks = quickPicksDeferred.await()
                val newReleases = newReleasesDeferred.await()
                val trending = trendingDeferred.await()
                val englishHits = englishHitsDeferred.await()
                val artists = artistsDeferred.await()

                // Convert history to songs
                val listenAgainSongs = listenAgain.map { history ->
                    com.sonicmusic.app.domain.model.Song(
                        id = history.songId,
                        title = history.title,
                        artist = history.artist,
                        duration = 0,
                        thumbnailUrl = history.thumbnailUrl
                    )
                }

                Result.success(
                    HomeContent(
                        listenAgain = listenAgainSongs,
                        quickPicks = quickPicks.getOrNull() ?: emptyList(),
                        newReleases = newReleases.getOrNull() ?: emptyList(),
                        trending = trending.getOrNull() ?: emptyList(),
                        englishHits = englishHits.getOrNull() ?: emptyList(),
                        artists = artists.getOrNull() ?: emptyList()
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}