package com.sonicmusic.app.domain.usecase

import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.HistoryRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.domain.service.RecommendationService
import javax.inject.Inject

data class HomeContent(
    val listenAgain: List<Song> = emptyList(),
    val quickPicks: List<Song> = emptyList(),
    val forgottenFavorites: List<Song> = emptyList(),
    val newReleases: List<Song> = emptyList(),
    val trending: List<Song> = emptyList(),
    val englishHits: List<Song> = emptyList(),
    val artists: List<Song> = emptyList() // List of songs representing artists for now
)

class GetHomeContentUseCase @Inject constructor(
    private val songRepository: SongRepository,
    private val historyRepository: HistoryRepository,
    private val recommendationService: RecommendationService
) {
    suspend operator fun invoke(): Result<HomeContent> {
        return try {
            // Fetch concurrently in real app, sequential for now
            val listenAgain = historyRepository.getRecentlyPlayed(limit = 15).getOrDefault(emptyList())
            val quickPicks = recommendationService.getPersonalizedSongs(limit = 20).getOrDefault(emptyList())
            val forgottenFavorites = recommendationService.getForgottenFavorites(limit = 15).getOrDefault(emptyList())
            val newReleases = songRepository.getNewReleases(limit = 25).getOrDefault(emptyList())
            val trending = songRepository.getTrending(limit = 30).getOrDefault(emptyList())
            val englishHits = songRepository.getEnglishHits(limit = 25).getOrDefault(emptyList())
            val artists = recommendationService.getTopArtistSongs(limit = 8).getOrDefault(emptyList())
            
            Result.success(
                HomeContent(
                    listenAgain = listenAgain,
                    quickPicks = quickPicks,
                    forgottenFavorites = forgottenFavorites,
                    newReleases = newReleases,
                    trending = trending,
                    englishHits = englishHits,
                    artists = artists
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
