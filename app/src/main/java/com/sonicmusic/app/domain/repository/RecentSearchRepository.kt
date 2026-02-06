package com.sonicmusic.app.domain.repository

import kotlinx.coroutines.flow.Flow

interface RecentSearchRepository {
    suspend fun addSearch(query: String)
    fun getRecentSearches(): Flow<List<String>>
    suspend fun clearHistory()
}
