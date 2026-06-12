package com.sonicmusic.app.domain.usecase

import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.SongRepository
import javax.inject.Inject

class SearchSongsUseCase @Inject constructor(
    private val songRepository: SongRepository
) {
    suspend operator fun invoke(query: String, limit: Int = 50): Result<List<Song>> {
        if (query.isBlank()) return Result.success(emptyList())
        
        // Search for songs - history is handled by ViewModel
        return songRepository.searchSongs(query, limit)
    }
}