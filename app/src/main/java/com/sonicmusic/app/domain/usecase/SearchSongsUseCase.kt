package com.sonicmusic.app.domain.usecase

import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.RecentSearchRepository
import com.sonicmusic.app.domain.repository.SongRepository
import javax.inject.Inject

class SearchSongsUseCase @Inject constructor(
    private val songRepository: SongRepository,
    private val recentSearchRepository: RecentSearchRepository
) {
    suspend operator fun invoke(query: String): Result<List<Song>> {
        if (query.isBlank()) return Result.success(emptyList())
        
        // Save to recent searches
        recentSearchRepository.addSearch(query)
        
        return songRepository.searchSongs(query)
    }
}
