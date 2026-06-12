package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.SearchSuggestionItem

/**
 * Repository for fetching autocomplete and rich suggestions
 */
interface SearchSuggestionRepository {
    /**
     * Get suggestions (both text autocomplete suggestions and rich content suggestions)
     * @param query Partial search query
     * @return Result containing Pair of List<String> (text suggestions) and List<SearchSuggestionItem> (rich suggestions)
     */
    suspend fun getSearchSuggestions(query: String): Result<Pair<List<String>, List<SearchSuggestionItem>>>
}
