package com.sonicmusic.app.domain.repository

interface CacheRepository {
    suspend fun getCachedStreamUrl(songId: String, quality: Int): String?
}
