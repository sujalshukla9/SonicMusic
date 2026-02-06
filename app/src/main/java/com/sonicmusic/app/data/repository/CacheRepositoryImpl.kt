package com.sonicmusic.app.data.repository

import com.sonicmusic.app.data.manager.CacheManager
import javax.inject.Inject

class CacheRepositoryImpl @Inject constructor(
    private val cacheManager: CacheManager
) : com.sonicmusic.app.domain.repository.CacheRepository {
    override suspend fun getCachedStreamUrl(songId: String, quality: Int): String? {
        return cacheManager.getCachedStreamUrl(songId, quality)
    }
}
