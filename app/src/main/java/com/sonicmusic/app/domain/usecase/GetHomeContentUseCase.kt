package com.sonicmusic.app.domain.usecase

import com.sonicmusic.app.domain.model.HomeContent
import com.sonicmusic.app.domain.repository.HistoryRepository
import com.sonicmusic.app.domain.repository.RecommendationRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.domain.repository.UserTasteRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class GetHomeContentUseCase @Inject constructor(
    private val songRepository: SongRepository,
    private val historyRepository: HistoryRepository,
    private val recommendationRepository: RecommendationRepository,
    private val userTasteRepository: UserTasteRepository
) {
    companion object {
        private const val HOME_SECTION_LIMIT = 100
        private const val TOP_ARTIST_SECTION_COUNT = 8
    }

    suspend operator fun invoke(): Result<HomeContent> {
        return try {
            coroutineScope {
                val listenAgainDeferred = async { 
                    try {
                        historyRepository.getRecentlyPlayedSongs(HOME_SECTION_LIMIT).first()
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                val quickPicksDeferred = async { 
                    recommendationRepository.getQuickPicks(HOME_SECTION_LIMIT) 
                }
                val newReleasesDeferred = async { 
                    songRepository.getNewReleases(HOME_SECTION_LIMIT) 
                }
                val trendingDeferred = async { 
                    songRepository.getTrending(HOME_SECTION_LIMIT) 
                }
                val englishHitsDeferred = async { 
                    songRepository.getEnglishHits(HOME_SECTION_LIMIT) 
                }
                val artistsDeferred = async { 
                    recommendationRepository.getTopArtistSongs(TOP_ARTIST_SECTION_COUNT) 
                }
                // Fetch personalized recommendations based on user taste
                val personalizedDeferred = async {
                    userTasteRepository.getPersonalizedMix(HOME_SECTION_LIMIT)
                }
                // Fetch forgotten favorites (songs played often but not recently)
                val forgottenDeferred = async {
                    recommendationRepository.getForgottenFavorites(HOME_SECTION_LIMIT)
                }

                // Wait for all results
                val listenAgain = listenAgainDeferred.await()
                val quickPicks = quickPicksDeferred.await()
                val newReleases = newReleasesDeferred.await()
                val trending = trendingDeferred.await()
                val englishHits = englishHitsDeferred.await()
                val artists = artistsDeferred.await()
                val personalized = personalizedDeferred.await()
                val forgotten = forgottenDeferred.await()

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
                        forgottenFavorites = forgotten.getOrNull() ?: emptyList(),
                        newReleases = newReleases.getOrNull() ?: emptyList(),
                        trending = trending.getOrNull() ?: emptyList(),
                        englishHits = englishHits.getOrNull() ?: emptyList(),
                        artists = artists.getOrNull() ?: emptyList(),
                        personalizedForYou = personalized.getOrNull() ?: emptyList()
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
