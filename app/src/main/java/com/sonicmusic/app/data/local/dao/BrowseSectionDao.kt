package com.sonicmusic.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sonicmusic.app.data.local.entity.BrowseSectionEntity

/**
 * DAO for the browse section cache.
 *
 * Provides CRUD operations for cached browse-endpoint results
 * (moods & genres, charts, trending albums/artists, mixed for you).
 * Entries are keyed by [BrowseSectionEntity.sectionKey] and expire
 * based on [BrowseSectionEntity.expiresAt].
 */
@Dao
interface BrowseSectionDao {

    /**
     * Get a cached section if it exists and hasn't expired.
     *
     * @param key The section key (e.g. "moods_and_genres")
     * @param now Current timestamp to check expiry against
     * @return The cached entity, or null if missing or expired
     */
    @Query("SELECT * FROM browse_section_cache WHERE sectionKey = :key AND expiresAt > :now LIMIT 1")
    suspend fun getValidCache(key: String, now: Long): BrowseSectionEntity?

    /**
     * Get a cached section by key regardless of expiry (used for instant cache-first display while refreshing in background).
     */
    @Query("SELECT * FROM browse_section_cache WHERE sectionKey = :key LIMIT 1")
    suspend fun getCache(key: String): BrowseSectionEntity?

    /**
     * Insert or replace a cache entry.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(entity: BrowseSectionEntity)

    /**
     * Clear all expired cache entries.
     *
     * @param now Current timestamp — entries with expiresAt < now are deleted
     */
    @Query("DELETE FROM browse_section_cache WHERE expiresAt < :now")
    suspend fun clearExpired(now: Long)

    /**
     * Clear a specific section's cache.
     *
     * @param key The section key to clear
     */
    @Query("DELETE FROM browse_section_cache WHERE sectionKey = :key")
    suspend fun clearSection(key: String)

    /**
     * Clear all cached browse sections.
     */
    @Query("DELETE FROM browse_section_cache")
    suspend fun clearAll()
}
