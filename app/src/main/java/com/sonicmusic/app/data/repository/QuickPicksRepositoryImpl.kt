package com.sonicmusic.app.data.repository

import android.util.Log
import com.sonicmusic.app.data.local.dao.PlaybackHistoryDao
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.ListenAgainRepository
import com.sonicmusic.app.domain.repository.QuickPicksRepository
import com.sonicmusic.app.domain.repository.UserTasteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quick Picks repository implementation fetching directly from YouTube Music.
 *
 * Sourced directly from YouTube Music Innertube API:
 * 1. Innertube seed recommendations based on recent playback history
 * 2. If new user without history: direct YouTube Music trending / home recommendations
 */
@Singleton
class QuickPicksRepositoryImpl @Inject constructor(
    private val listenAgainRepository: ListenAgainRepository,
    private val userTasteRepository: UserTasteRepository,
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val youTubeiService: YouTubeiService,
    private val settingsDataStore: SettingsDataStore
) : QuickPicksRepository {

    companion object {
        private const val TAG = "QuickPicks"
        private const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes
        private const val FALLBACK_COUNTRY_CODE = "US"
    }

    /** In-memory cache */
    @Volatile
    private var cachedPicks: List<Song>? = null
    @Volatile
    private var cachedAtMs: Long = 0L
    @Volatile
    private var cachedRegionCode: String? = null

    override suspend fun getQuickPicks(limit: Int, forceRefresh: Boolean): List<Song> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val regionCode = resolveCacheRegionCode()

        if (!forceRefresh) {
            cachedPicks?.takeIf {
                now - cachedAtMs < CACHE_TTL_MS &&
                    it.size >= (limit / 2).coerceAtLeast(1) &&
                    cachedRegionCode == regionCode
            }?.let {
                Log.d(TAG, "♻️ Returning cached Quick Picks (${it.size} songs)")
                return@withContext it.take(limit)
            }
        }

        val startMs = System.currentTimeMillis()
        try {
            val result = fetchDirectYtmQuickPicks(limit)

            cachedPicks = result
            cachedAtMs = now
            cachedRegionCode = regionCode

            Log.d(TAG, "⚡ Quick Picks fetched directly from YTM: ${result.size} songs in ${System.currentTimeMillis() - startMs}ms")
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "❌ Quick Picks failed", e)
            val fallback = fallbackQuickPicks(limit)
            cachedPicks = fallback
            cachedAtMs = now
            cachedRegionCode = regionCode
            fallback
        }
    }

    private suspend fun fetchDirectYtmQuickPicks(limit: Int): List<Song> = coroutineScope {
        val safeLimit = limit.coerceAtLeast(5)
        val allSongs = mutableListOf<Song>()

        val recentSongIds = runCatching { playbackHistoryDao.getRecentSongIds(6) }
            .getOrDefault(emptyList())

        if (recentSongIds.isNotEmpty()) {
            val seedIds = recentSongIds.take(3)
            val perSeedLimit = (safeLimit / seedIds.size).coerceAtLeast(6)

            val seedRecs = seedIds.map { seedId ->
                async {
                    youTubeiService.getSongRecommendationsFromInnertube(
                        videoId = seedId,
                        limit = perSeedLimit
                    ).getOrNull().orEmpty().filter { it.isStrictSong() }
                }
            }.awaitAll().flatten()

            allSongs.addAll(seedRecs)
            Log.d(TAG, "📡 YTM seed recommendations: ${seedRecs.size} songs")
        }

        if (allSongs.size < safeLimit) {
            val tasteProfile = userTasteRepository.getUserTasteProfile()
            if (tasteProfile.topArtists.isNotEmpty()) {
                val topArtist = tasteProfile.topArtists.first()
                val artistSongs = youTubeiService.searchSongs("$topArtist songs", safeLimit)
                    .getOrNull().orEmpty().filter { it.isStrictSong() }
                allSongs.addAll(artistSongs)
            }
        }

        if (allSongs.size < safeLimit) {
            val trendingSongs = youTubeiService.getTrendingSongs(safeLimit)
                .getOrNull().orEmpty().filter { it.isStrictSong() }
            allSongs.addAll(trendingSongs)
        }

        allSongs
            .distinctBy { it.id }
            .shuffled()
            .take(safeLimit)
    }

    private suspend fun fallbackQuickPicks(limit: Int): List<Song> {
        val listenAgain = runCatching { listenAgainRepository.getListenAgainSongs(limit) }
            .getOrDefault(emptyList())
        if (listenAgain.isNotEmpty()) return listenAgain.take(limit)

        return youTubeiService.getTrendingSongs(limit)
            .getOrNull()
            .orEmpty()
            .filter { it.isStrictSong() }
            .take(limit)
    }

    private suspend fun resolveCacheRegionCode(): String {
        return RegionalRecommendationHelper.normalizeCountryCode(settingsDataStore.countryCode.first())
            ?: RegionalRecommendationHelper.normalizeCountryCode(Locale.getDefault().country)
            ?: FALLBACK_COUNTRY_CODE
    }

    private fun Song.isStrictSong(): Boolean {
        return when (contentType) {
            ContentType.SONG -> true
            ContentType.UNKNOWN -> duration == 0 || duration in 60..600
            else -> false
        }
    }
}
