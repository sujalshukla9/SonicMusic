package com.sonicmusic.app.features.search.data

import com.sonicmusic.app.data.local.dao.RecentSearchDao
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.data.local.entity.RecentSearchEntity
import com.sonicmusic.app.domain.model.RecentSearch
import com.sonicmusic.app.domain.repository.RecentSearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecentSearchRepositoryImpl @Inject constructor(
    private val recentSearchDao: RecentSearchDao,
    private val settingsDataStore: SettingsDataStore,
) : RecentSearchRepository {

    override fun getRecentSearches(limit: Int): Flow<List<RecentSearch>> {
        return recentSearchDao.getRecentSearches(limit).map { entities ->
            entities
                .filter { it.query.isNotBlank() }
                .distinctBy { it.query.trim().lowercase() }
                .map { it.toRecentSearch() }
        }
    }

    override suspend fun addSearch(query: String, resultId: String?, resultType: String?) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return

        // Respect the user's Pause History preference
        if (settingsDataStore.pauseHistory.first()) {
            return
        }

        // Delete any existing duplicate case-insensitively so updating query timestamp stays clean
        recentSearchDao.deleteSearch(trimmed)

        val entity = RecentSearchEntity(
            query = trimmed,
            searchedAt = System.currentTimeMillis(),
            resultId = resultId,
            resultType = resultType
        )
        recentSearchDao.insertSearch(entity)
        recentSearchDao.pruneOldSearches(20)
    }

    override suspend fun deleteSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isNotBlank()) {
            recentSearchDao.deleteSearch(trimmed)
        }
    }

    override suspend fun clearAllSearches() {
        recentSearchDao.clearAll()
    }

    private fun RecentSearchEntity.toRecentSearch(): RecentSearch {
        return RecentSearch(
            query = query,
            searchedAt = searchedAt,
            resultId = resultId,
            resultType = resultType
        )
    }
}