package com.sonicmusic.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.sonicmusic.app.data.local.entity.ArtistPageCacheEntity

@Dao
interface ArtistPageDao {

    @Query("SELECT * FROM artist_pages WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun getArtistPage(cacheKey: String): ArtistPageCacheEntity?

    @Upsert
    suspend fun upsertArtistPage(entity: ArtistPageCacheEntity)

    @Query("DELETE FROM artist_pages WHERE cacheKey = :cacheKey")
    suspend fun deleteArtistPage(cacheKey: String)

    @Query("DELETE FROM artist_pages WHERE fetchedAt < :threshold")
    suspend fun purgeOldCache(threshold: Long)
}
