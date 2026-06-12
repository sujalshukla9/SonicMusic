package com.sonicmusic.app.domain.usecase

import com.sonicmusic.app.domain.repository.SongRepository
import javax.inject.Inject

class GetSearchSuggestionsUseCase @Inject constructor(
    private val songRepository: SongRepository,
) {
    suspend operator fun invoke(query: String): Result<List<String>> {
        if (query.isBlank() || query.length < 2) return Result.success(emptyList())
        return songRepository.getSearchSuggestions(query)
    }
}
