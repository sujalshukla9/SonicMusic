package com.sonicmusic.app.data.repository

import com.sonicmusic.app.domain.repository.RecentSearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class RecentSearchRepositoryImpl @Inject constructor(
    private val dao: com.sonicmusic.app.data.local.dao.SearchHistoryDao
) : RecentSearchRepository {

    override suspend fun addSearch(query: String) {
        dao.insertSearch(
            com.sonicmusic.app.data.local.entity.SearchHistoryEntity(
                query = query,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    override fun getRecentSearches(): Flow<List<String>> {
        return dao.getRecentSearches()
    }

    override suspend fun clearHistory() {
        dao.clearHistory()
    }
}
