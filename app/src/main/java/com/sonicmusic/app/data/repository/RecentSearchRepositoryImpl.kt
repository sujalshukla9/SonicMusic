package com.sonicmusic.app.data.repository

import com.sonicmusic.app.data.local.dao.RecentSearchDao
import com.sonicmusic.app.data.local.entity.RecentSearchEntity
import com.sonicmusic.app.domain.model.RecentSearch
import com.sonicmusic.app.domain.repository.RecentSearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecentSearchRepositoryImpl @Inject constructor(
    private val recentSearchDao: RecentSearchDao
) : RecentSearchRepository {

    override fun getRecentSearches(limit: Int): Flow<List<RecentSearch>> {
        return recentSearchDao.getRecentSearches(limit).map { entities ->
            entities.map { it.toRecentSearch() }
        }
    }

    override suspend fun addSearch(query: String) {
        val entity = RecentSearchEntity(
            query = query,
            searchedAt = System.currentTimeMillis()
        )
        recentSearchDao.insertSearch(entity)
        recentSearchDao.pruneOldSearches(20)
    }

    override suspend fun deleteSearch(query: String) {
        recentSearchDao.deleteSearch(query)
    }

    override suspend fun clearAllSearches() {
        recentSearchDao.clearAll()
    }

    private fun RecentSearchEntity.toRecentSearch(): RecentSearch {
        return RecentSearch(
            query = query,
            searchedAt = searchedAt
        )
    }
}