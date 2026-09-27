package com.sonicmusic.app.features.recommendations.data

import android.util.Log
import com.sonicmusic.app.data.local.dao.PlaybackHistoryDao
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.Artist
import com.sonicmusic.app.domain.model.ArtistSection
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.HomeContent
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.RecommendationRepository
import com.sonicmusic.app.domain.repository.UserTasteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Smart Recommendation Repository with User Taste Integration
 * 
 * Logic for each section:
 * - Quick Picks: Uses user's top artists + time-of-day preferences
 * - Listen Again: Retrieved from playback history (handled by HistoryRepository)
 * - For You: Uses getPersonalizedMix from UserTasteRepository
 * - Top Artists: Combines user's top artists with popular artists
 */
@Singleton
class RecommendationRepositoryImpl @Inject constructor(
    private val youTubeiService: YouTubeiService,
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val userTasteRepository: UserTasteRepository,
    private val settingsDataStore: SettingsDataStore,
    private val recommendationEngine: com.sonicmusic.app.features.recommendations.engine.RecommendationEngine
) : RecommendationRepository {

    companion object {
        private const val TAG = "RecommendationRepo"
        private const val FALLBACK_COUNTRY_CODE = "US"
        private const val REGION_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val FORGOTTEN_FAVORITES_WINDOW_MS = 14L * 24 * 60 * 60 * 1000
        private const val FORGOTTEN_FAVORITES_MIN_PLAY_COUNT = 2
    }

    @Volatile
    private var cachedRegionContext: RegionContext? = null
    @Volatile
    private var cachedRegionLoadedAtMs: Long = 0L

    /**
     * Get Quick Picks - SMART PERSONALIZED RECOMMENDATIONS
     * 
     * Logic:
     * 1. Pull song recommendations from Innertube using recent/most-played seed songs
     * 2. If still short: use top artists + time-of-day preference
     * 3. For new users: use region-aware trending + discovery query
     * 4. Mix and dedupe for variety
     */
    override suspend fun getQuickPicks(limit: Int): Result<List<Song>> {
        return try {
            Log.d(TAG, "Getting smart quick picks...")
            val safeLimit = limit.coerceAtLeast(1)
            
            // Get user's taste profile
            val tasteProfile = userTasteRepository.getUserTasteProfile()
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val region = getRegionContext()
            
            val allSongs = mutableListOf<Song>()
            val seedSongIds = linkedSetOf<String>().apply {
                addAll(tasteProfile.mostPlayedSongIds)
                addAll(playbackHistoryDao.getRecentSongIds((safeLimit * 2).coerceAtLeast(8)))
            }

            if (seedSongIds.isNotEmpty()) {
                val innertubeSongs = fetchSeedRecommendationsFromInnertube(
                    seedSongIds = seedSongIds.toList(),
                    limit = safeLimit
                )
                allSongs.addAll(innertubeSongs)
                Log.d(TAG, "Innertube recommendations from seeds: ${innertubeSongs.size}")
            }
            
            if (allSongs.size < safeLimit && tasteProfile.topArtists.isNotEmpty()) {
                // User has listening history - use personalized queries
                Log.d(TAG, "User has history: ${tasteProfile.topArtists.size} top artists")
                val remainingSlots = (safeLimit - allSongs.size).coerceAtLeast(1)
                val queryLimit = (remainingSlots / 2).coerceAtLeast(1)
                
                // Strategy 1: Search by top artist (50% of results)
                val topArtist = tasteProfile.topArtists.first()
                val artistQuery = "$topArtist best songs"
                Log.d(TAG, "Searching: $artistQuery")
                
                // Strategy 2: Similar music to user's taste + time context (50% of results)
                val moodQuery = RegionalRecommendationHelper.timeBasedQuery(
                    hour = currentHour,
                    countryCode = region.countryCode,
                    countryName = region.countryName
                )
                Log.d(TAG, "Time-based query: $moodQuery")

                coroutineScope {
                    val artistDeferred = async { youTubeiService.searchSongs(artistQuery, queryLimit).getOrNull().orEmpty() }
                    val moodDeferred = async { youTubeiService.searchSongs(moodQuery, queryLimit).getOrNull().orEmpty() }

                    allSongs.addAll(artistDeferred.await().filter { it.isStrictSong() })
                    allSongs.addAll(moodDeferred.await().filter { it.isStrictSong() })
                }
                
            } else if (allSongs.size < safeLimit) {
                // New user - use dynamic regional cold start (charts + trending + new releases)
                Log.d(TAG, "New user - using dynamic regional recommendations (${region.countryCode})")
                val remainingSlots = (safeLimit - allSongs.size).coerceAtLeast(1)

                coroutineScope {
                    val chartsDeferred = async {
                        youTubeiService.browseCharts().getOrNull()?.items.orEmpty().take(remainingSlots)
                    }
                    val trendingDeferred = async {
                        youTubeiService.getTrendingSongs(remainingSlots).getOrNull().orEmpty()
                    }
                    val newReleasesDeferred = async {
                        youTubeiService.getNewReleases(remainingSlots).getOrNull().orEmpty()
                    }

                    val chartSongs = chartsDeferred.await().filter { it.isStrictSong() }
                    val trendingSongs = trendingDeferred.await().filter { it.isStrictSong() }
                    val releaseSongs = newReleasesDeferred.await().filter { it.isStrictSong() }

                    allSongs.addAll(chartSongs)
                    allSongs.addAll(trendingSongs)
                    allSongs.addAll(releaseSongs)

                    // If still under limit, use dynamic contextual discovery query
                    if (allSongs.size < safeLimit) {
                        val query = RegionalRecommendationHelper.timeBasedQuery(
                            hour = currentHour,
                            countryCode = region.countryCode,
                            countryName = region.countryName
                        )
                        val querySongs = youTubeiService.searchSongs(query, safeLimit - allSongs.size).getOrNull().orEmpty()
                        allSongs.addAll(querySongs.filter { it.isStrictSong() })
                    }
                }
            }

            // Safety fallback
            if (allSongs.isEmpty()) {
                youTubeiService.getTrendingSongs(safeLimit).onSuccess { songs ->
                    allSongs.addAll(songs.filter { it.isStrictSong() })
                }
            }
            // Score, rank, and diversify through the recommendation engine
            val uniqueSongs = if (allSongs.size > 1) {
                recommendationEngine.rankCandidates(
                    candidates = allSongs.distinctBy { it.id },
                    targetSize = safeLimit,
                    lambdaDiversity = com.sonicmusic.app.features.recommendations.engine.DiversityReranker.LAMBDA_DEFAULT,
                    sectionWeightOverrides = recommendationEngine.QUICK_PICKS_WEIGHTS
                )
            } else {
                allSongs.distinctBy { it.id }.take(safeLimit)
            }
            
            Log.d(TAG, "Quick picks ready: ${uniqueSongs.size} songs (scored & ranked)")
            Result.success(uniqueSongs)
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Quick picks error", e)
            Result.failure(e)
        }
    }

    private suspend fun fetchSeedRecommendationsFromInnertube(
        seedSongIds: List<String>,
        limit: Int
    ): List<Song> = coroutineScope {
        if (seedSongIds.isEmpty() || limit <= 0) return@coroutineScope emptyList()

        val targetSeedCount = seedSongIds.take(2)
        val perSeedLimit = (limit / targetSeedCount.size.coerceAtLeast(1))
            .coerceAtLeast(6)
            .coerceAtMost(16)

        targetSeedCount.map { seedId ->
            async {
                youTubeiService.getSongRecommendationsFromInnertube(
                    videoId = seedId,
                    limit = perSeedLimit
                ).getOrNull()
                    .orEmpty()
                    .filter { it.isStrictSong() }
            }
        }.awaitAll()
            .flatten()
            .distinctBy { it.id }
            .take(limit)
    }

    /**
     * Get Forgotten Favorites - Songs played often but not recently
     * 
     * Logic:
     * 1. Get most played songs (by play count)
     * 2. Filter out songs played in last 14 days
     * 3. Return rediscovery suggestions
     */
    override suspend fun getForgottenFavorites(limit: Int): Result<List<Song>> {
        return try {
            Log.d(TAG, "Getting forgotten favorites...")

            val safeLimit = limit.coerceAtLeast(1)
            val staleCutoff = System.currentTimeMillis() - FORGOTTEN_FAVORITES_WINDOW_MS
            val recentIds = playbackHistoryDao.getRecentSongIds((safeLimit * 4).coerceAtLeast(24)).toSet()
            val rediscoveryCandidates = playbackHistoryDao.getRediscoveryCandidates(
                (safeLimit * 8).coerceAtMost(250)
            )

            val forgotten = rediscoveryCandidates
                .asSequence()
                .filter { it.playCount >= FORGOTTEN_FAVORITES_MIN_PLAY_COUNT }
                .filter { it.lastPlayedAt <= staleCutoff }
                .filterNot { candidate -> recentIds.contains(candidate.songId) }
                .map { candidate ->
                    Song(
                        id = candidate.songId,
                        title = candidate.title,
                        artist = candidate.artist,
                        duration = 0,
                        thumbnailUrl = candidate.thumbnailUrl,
                        contentType = ContentType.SONG
                    )
                }
                .distinctBy { song -> song.id }
                .take(safeLimit * 2)
                .toList()

            // Keep the section populated when history is shallow.
            val candidatePool = if (forgotten.isNotEmpty()) {
                forgotten
            } else {
                rediscoveryCandidates
                    .asSequence()
                    .filterNot { candidate -> recentIds.contains(candidate.songId) }
                    .map { candidate ->
                        Song(
                            id = candidate.songId,
                            title = candidate.title,
                            artist = candidate.artist,
                            duration = 0,
                            thumbnailUrl = candidate.thumbnailUrl,
                            contentType = ContentType.SONG
                        )
                    }
                    .distinctBy { song -> song.id }
                    .take(safeLimit * 2)
                    .toList()
            }

            val resultSongs = if (candidatePool.size > 1) {
                recommendationEngine.rankCandidates(
                    candidates = candidatePool,
                    targetSize = safeLimit,
                    lambdaDiversity = com.sonicmusic.app.features.recommendations.engine.DiversityReranker.LAMBDA_MILD,
                    sectionWeightOverrides = recommendationEngine.LISTEN_AGAIN_WEIGHTS
                )
            } else {
                candidatePool.take(safeLimit)
            }

            Log.d(TAG, "Forgotten favorites: ${resultSongs.size} songs (scored & ranked)")
            Result.success(resultSongs)
            
        } catch (e: Exception) {
            Log.e(TAG, "Forgotten favorites error", e)
            Result.failure(e)
        }
    }

    /**
     * Get Top Artist Songs - User's favorite artists + popular artists
     * 
     * Logic:
     * 1. Get user's top played artists from history
     * 2. Mix with popular artists for variety
     * 3. Fetch top songs for each artist
     */
    override suspend fun getTopArtistSongs(limit: Int): Result<List<ArtistSection>> {
        return try {
            Log.d(TAG, "Getting top artist songs...")
            
            // Get user's top artists from taste profile
            val tasteProfile = userTasteRepository.getUserTasteProfile()
            val userTopArtists = tasteProfile.topArtists.take(3)
            val regionalPopularArtists: List<String> = if (userTopArtists.size < limit) {
                val chartSongs: List<Song> = youTubeiService.browseCharts().getOrNull()?.items.orEmpty().take(20)
                val trendingSongs: List<Song> = if (chartSongs.isEmpty()) youTubeiService.getTrendingSongs(15).getOrNull().orEmpty() else emptyList()
                (chartSongs + trendingSongs)
                    .map { song: Song -> song.artist.trim() }
                    .filter { artist: String -> artist.isNotBlank() && !artist.equals("Unknown Artist", ignoreCase = true) }
                    .distinct()
                    .take(5)
            } else {
                emptyList()
            }
            
            // Mix user's artists with popular artists
            val artistsToQuery: List<String> = if (userTopArtists.isNotEmpty()) {
                val combined = userTopArtists + regionalPopularArtists
                    .filter { !userTopArtists.contains(it) }
                    .take(2)
                combined.take(limit)
            } else {
                regionalPopularArtists.shuffled().take(limit)
            }
            
            Log.d(TAG, "Artists to query: $artistsToQuery")
            
            val artistSections = coroutineScope {
                artistsToQuery.map { artistName: String ->
                    async {
                        val query = "$artistName top songs"
                        Log.d(TAG, "Searching: $query")
                        val strictSongs = youTubeiService.searchSongs(query, 8)
                            .getOrNull()
                            .orEmpty()
                            .filter { it.isStrictSong() }
                        if (strictSongs.isEmpty()) null else {
                            ArtistSection(
                                artist = Artist(
                                    id = artistName.lowercase().replace(" ", "_"),
                                    name = artistName,
                                    songCount = strictSongs.size
                                ),
                                songs = strictSongs
                            )
                        }
                    }
                }.awaitAll()
                    .filterNotNull()
                    .take(limit.coerceAtLeast(1))
            }
            
            Log.d(TAG, "Got ${artistSections.size} artist sections")
            Result.success(artistSections)
            
        } catch (e: Exception) {
            Log.e(TAG, "Top artist songs error", e)
            Result.failure(e)
        }
    }

    override fun getHomeContent(): Flow<Result<HomeContent>> = flow {
        emit(Result.success(HomeContent()))
    }

    private suspend fun getRegionContext(): RegionContext {
        val now = System.currentTimeMillis()
        val countryCode = RegionalRecommendationHelper.normalizeCountryCode(settingsDataStore.countryCode.first())
            ?: RegionalRecommendationHelper.normalizeCountryCode(Locale.getDefault().country)
            ?: FALLBACK_COUNTRY_CODE

        val countryName = RegionalRecommendationHelper.canonicalCountryName(
            countryCode = countryCode,
            cachedName = settingsDataStore.countryName.first()
        )

        cachedRegionContext?.takeIf { cached ->
            now - cachedRegionLoadedAtMs < REGION_CACHE_TTL_MS &&
                cached.countryCode == countryCode &&
                cached.countryName == countryName
        }?.let { cached ->
            return cached
        }

        return RegionContext(countryCode = countryCode, countryName = countryName).also {
            cachedRegionContext = it
            cachedRegionLoadedAtMs = now
        }
    }

    private data class RegionContext(
        val countryCode: String,
        val countryName: String
    )

    private fun Song.isStrictSong(): Boolean {
        if (!com.sonicmusic.app.data.remote.source.InnertubeConfig.isAuthenticSong(title, artist, duration)) {
            return false
        }
        return when (contentType) {
            ContentType.SONG -> true
            ContentType.UNKNOWN -> duration == 0 || duration in 60..600
            else -> false
        }
    }
}
