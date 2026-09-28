package com.sonicmusic.app.features.home.data

import android.util.Log
import com.sonicmusic.app.data.local.dao.BrowseSectionDao
import com.sonicmusic.app.data.local.entity.BrowseSectionEntity
import com.sonicmusic.app.domain.model.HomeContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two-level cache for Home page recommendations (Memory + Room Disk Cache).
 *
 * Implements Cache-First + Background Refresh architecture:
 * 1. Memory Cache (< 1ms read) for hot navigation
 * 2. Disk Cache (< 15ms read) persisted across app restarts
 * 3. Configurable TTL (default 30 min) with stale-while-revalidate fallback
 */
@Singleton
class HomeCacheDataSource @Inject constructor(
    private val browseSectionDao: BrowseSectionDao
) {
    companion object {
        private const val TAG = "HomeCacheDataSource"
        private const val KEY_HOME_SNAPSHOT = "home_content_snapshot"
        const val DEFAULT_CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Volatile
    private var memoryCache: HomeContent? = null

    @Volatile
    private var memoryCacheTimestamp: Long = 0L

    /**
     * Get cached HomeContent immediately.
     * Checks memory cache first, then disk cache.
     *
     * @param ignoreTtl If true, returns cached data even if expired (stale-while-revalidate)
     * @return Cached [HomeContent] or null if no cache is available.
     */
    suspend fun getCachedHome(ignoreTtl: Boolean = true): HomeContent? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        // 1. Memory cache check
        val mem = memoryCache
        if (mem != null && (ignoreTtl || (now - memoryCacheTimestamp < DEFAULT_CACHE_TTL_MS))) {
            Log.d(TAG, "Home memory cache hit (age: ${now - memoryCacheTimestamp}ms)")
            return@withContext mem
        }

        // 2. Disk cache check via Room
        try {
            val entity = if (ignoreTtl) {
                browseSectionDao.getCache(KEY_HOME_SNAPSHOT)
            } else {
                browseSectionDao.getValidCache(KEY_HOME_SNAPSHOT, now)
            }

            if (entity != null && entity.jsonData.isNotBlank()) {
                val deserialized = json.decodeFromString<HomeContent>(entity.jsonData)
                if (deserialized.hasAnyContent()) {
                    memoryCache = deserialized
                    memoryCacheTimestamp = entity.cachedAt
                    Log.d(TAG, "Home disk cache hit from Room (cachedAt: ${entity.cachedAt})")
                    return@withContext deserialized
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading Home disk cache", e)
        }

        return@withContext null
    }

    /**
     * Check if the currently available cache is fresh according to TTL.
     */
    suspend fun isCacheValid(): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (memoryCache != null && (now - memoryCacheTimestamp < DEFAULT_CACHE_TTL_MS)) {
            return@withContext true
        }

        try {
            val validEntity = browseSectionDao.getValidCache(KEY_HOME_SNAPSHOT, now)
            return@withContext validEntity != null
        } catch (e: Exception) {
            return@withContext false
        }
    }

    /**
     * Persist HomeContent to both Memory and Room Disk Cache.
     */
    suspend fun saveHomeCache(content: HomeContent, ttlMs: Long = DEFAULT_CACHE_TTL_MS) = withContext(Dispatchers.IO) {
        if (!content.hasAnyContent()) return@withContext

        val now = System.currentTimeMillis()
        memoryCache = content
        memoryCacheTimestamp = now

        try {
            val serialized = json.encodeToString(content)
            val entity = BrowseSectionEntity(
                sectionKey = KEY_HOME_SNAPSHOT,
                jsonData = serialized,
                cachedAt = now,
                expiresAt = now + ttlMs
            )
            browseSectionDao.insertOrReplace(entity)
            Log.d(TAG, "Saved HomeContent to memory and disk cache successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting HomeContent to Room cache", e)
        }
    }

    /**
     * Clear both memory and disk caches.
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        memoryCache = null
        memoryCacheTimestamp = 0L
        try {
            browseSectionDao.clearSection(KEY_HOME_SNAPSHOT)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing Home disk cache", e)
        }
    }
}
