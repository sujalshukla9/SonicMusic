package com.sonicmusic.app.domain.usecase

import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.CacheRepository
import com.sonicmusic.app.domain.repository.HistoryRepository
import com.sonicmusic.app.domain.repository.SongRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

// Placeholder for PlayerService interface if not in domain
// Typically PlayerService is in Service layer, but UseCase might invoke a repository that controls it or a Controller.
// PRD says PlaySongUseCase injects PlayerService. 
// However, direct dependency on Android Service from Domain is tricky.
// Usually we have a PlayerController interface in Domain.
// For now, I'll refer to a PlayerController interface.
// I'll create PlayerController in domain as well.

import com.sonicmusic.app.domain.service.PlayerController


class PlaySongUseCase @Inject constructor(
    private val playerController: PlayerController,
    private val historyRepository: HistoryRepository,
    private val cacheRepository: CacheRepository,
    private val songRepository: SongRepository
) {
    // Current quality - hardcoded for now or injected
    private val currentQuality = 192 // High

    suspend operator fun invoke(song: Song, replaceQueue: Boolean = false): Result<Unit> {
        return try {
            // Get stream URL (Cache -> Remote)
            val streamUrl = cacheRepository.getCachedStreamUrl(song.id, currentQuality)
                ?: songRepository.getStreamUrl(song.id, currentQuality).getOrThrow()
            
            // Play song
            if (replaceQueue) {
                playerController.playNow(song, streamUrl)
            } else {
                playerController.addToQueue(song, streamUrl)
            }
            
            // Record in history
            historyRepository.recordPlayback(song.id)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
