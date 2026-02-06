package com.sonicmusic.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sonicmusic.app.data.local.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearch(search: SearchHistoryEntity)

    @Query("SELECT query FROM search_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentSearches(limit: Int = 10): Flow<List<String>>

    @Query("DELETE FROM search_history")
    suspend fun clearHistory()
}
