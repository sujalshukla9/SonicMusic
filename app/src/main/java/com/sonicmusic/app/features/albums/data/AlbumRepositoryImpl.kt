package com.sonicmusic.app.features.albums.data

import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.AlbumDetails
import com.sonicmusic.app.domain.repository.AlbumRepository
import android.util.LruCache
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlbumRepositoryImpl @Inject constructor(
    private val youTubeiService: YouTubeiService
) : AlbumRepository {

    private data class CacheEntry(
        val data: AlbumDetails,
        val timestamp: Long
    )

    private val cache = LruCache<String, CacheEntry>(50)
    private val cacheTtlMs = 24 * 60 * 60 * 1000L // 24 hours

    override suspend fun getAlbumDetails(browseId: String, forceRefresh: Boolean): Result<AlbumDetails> {
        val normalizedId = normalizeAlbumId(browseId)
        if (normalizedId.isBlank()) {
            return Result.failure(IllegalArgumentException("Album browseId is empty"))
        }

        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = cache.get(normalizedId)
            if (cached != null && now - cached.timestamp < cacheTtlMs) {
                android.util.Log.d("AlbumRepo", "Album cache hit for ID: $normalizedId")
                return Result.success(cached.data)
            }
        }

        android.util.Log.d("AlbumRepo", "Album cache miss for ID: $normalizedId. Fetching from network.")
        return youTubeiService.getAlbumDetailsFromInnertube(normalizedId)
            .onSuccess { details ->
                cache.put(normalizedId, CacheEntry(details, now))
            }
    }

    private fun normalizeAlbumId(id: String): String {
        val trimmed = id.trim()
        return when {
            trimmed.startsWith("VLMPRE") -> trimmed.removePrefix("VL")
            else -> trimmed
        }
    }
}
