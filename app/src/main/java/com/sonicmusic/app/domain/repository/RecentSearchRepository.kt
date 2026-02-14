package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.RecentSearch
import kotlinx.coroutines.flow.Flow

interface RecentSearchRepository {
    fun getRecentSearches(limit: Int = 5): Flow<List<RecentSearch>>
    suspend fun addSearch(query: String, resultId: String? = null, resultType: String? = null)
    suspend fun deleteSearch(query: String)
    suspend fun clearAllSearches()
}