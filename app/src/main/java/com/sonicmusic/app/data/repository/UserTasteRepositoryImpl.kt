package com.sonicmusic.app.data.repository

import android.util.Log
import com.sonicmusic.app.data.local.dao.PlaybackHistoryDao
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.ListeningPattern
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.UserTasteProfile
import com.sonicmusic.app.domain.repository.UserTasteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserTasteRepositoryImpl @Inject constructor(
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val youTubeiService: YouTubeiService,
    private val settingsDataStore: SettingsDataStore
) : UserTasteRepository {

    companion object {
        private const val TAG = "UserTasteRepo"
        private const val FALLBACK_COUNTRY_CODE = "US"
        private const val REGION_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val TASTE_CACHE_TTL_MS = 5 * 60 * 1000L
    }

    @Volatile
    private var cachedRegionContext: RegionContext? = null
    @Volatile
    private var cachedRegionLoadedAtMs: Long = 0L
    @Volatile
    private var cachedTasteProfile: UserTasteProfile? = null
    @Volatile
    private var cachedTasteLoadedAtMs: Long = 0L

    override suspend fun getUserTasteProfile(): UserTasteProfile = withContext(Dispatchers.IO) {
        // Return cached profile if fresh
        val now = System.currentTimeMillis()
        cachedTasteProfile?.takeIf { now - cachedTasteLoadedAtMs < TASTE_CACHE_TTL_MS }?.let {
            return@withContext it
        }

        val startMs = System.currentTimeMillis()
        try {
            val region = getRegionContext()
            val topArtists = getTopArtists()
            val listeningPattern = analyzeListeningPattern()
            val completionRate = calculateCompletionRate()
            val skipRate = calculateSkipRate()
            val avgDuration = playbackHistoryDao.getAveragePlayDuration() ?: 0L
            val searchQueries = buildSearchQueries(
                topArtists = topArtists,
                countryCode = region.countryCode,
                countryName = region.countryName
            )
            val topGenres = getTopGenres()
            val mostPlayed = playbackHistoryDao.getMostPlayedSongs(10)
                .map { it.songId }

            UserTasteProfile(
                topArtists = topArtists,
                preferredLanguages = inferLanguagePreferences(),
                listeningPattern = listeningPattern,
                completionRate = completionRate,
                avgSessionDuration = avgDuration,
                topSearchQueries = searchQueries,
                topGenres = topGenres,
                skipRate = skipRate,
                mostPlayedSongIds = mostPlayed
            ).also { profile ->
                cachedTasteProfile = profile
                cachedTasteLoadedAtMs = System.currentTimeMillis()
                Log.d(TAG, "⏱️ Taste profile built in ${System.currentTimeMillis() - startMs}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error building taste profile", e)
            UserTasteProfile.DEFAULT
        }
    }

    override suspend fun updateTasteFromPlayback(
        song: Song,
        playDuration: Int,
        completed: Boolean
    ) {
        // Taste is auto-updated via PlaybackHistoryDao
        // This method can be used for real-time preference updates if needed
        Log.d(TAG, "Playback recorded: ${song.title} by ${song.artist}, completed=$completed")
    }

    override suspend fun getPersonalizedSearchQueries(): List<String> = withContext(Dispatchers.IO) {
        val region = getRegionContext()
        val topArtists = getTopArtists()
        buildSearchQueries(topArtists, region.countryCode, region.countryName)
    }

    override suspend fun getPersonalizedMix(limit: Int): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val safeLimit = limit.coerceAtLeast(1)
            val tasteProfile = getUserTasteProfile()
            val seedSongIds = linkedSetOf<String>().apply {
                addAll(tasteProfile.mostPlayedSongIds)
                addAll(playbackHistoryDao.getRecentSongIds((safeLimit * 2).coerceAtLeast(8)))
            }

            val allSongs = mutableListOf<Song>()

            if (seedSongIds.isNotEmpty()) {
                val seedRecommendations = fetchSeedRecommendationsFromInnertube(
                    seedSongIds = seedSongIds.toList(),
                    limit = safeLimit
                )
                allSongs.addAll(seedRecommendations)
                Log.d(TAG, "📡 Personalized Innertube seed recs: ${seedRecommendations.size}")
            }

            if (allSongs.size < safeLimit) {
                val queries = if (tasteProfile.topSearchQueries.isNotEmpty()) {
                    tasteProfile.topSearchQueries
                } else {
                    getPersonalizedSearchQueries()
                }

                if (queries.isNotEmpty()) {
                    val remaining = (safeLimit - allSongs.size).coerceAtLeast(1)
                    val queryCount = queries.take(4).size.coerceAtLeast(1)
                    val songsPerQuery = (remaining / queryCount).coerceAtLeast(5)

                    val querySongs = coroutineScope {
                        queries.take(4).map { query ->
                            async {
                                Log.d(TAG, "Fetching personalized query: $query")
                                youTubeiService.searchSongs(query, songsPerQuery)
                                    .getOrNull()
                                    .orEmpty()
                                    .filter { it.isStrictSong() }
                            }
                        }.awaitAll().flatten()
                    }
                    allSongs.addAll(querySongs)
                }
            }

            if (allSongs.isEmpty()) {
                // Fallback for new users: region-aware trending first, then region-aware query.
                youTubeiService.getTrendingSongs(safeLimit).getOrNull()?.let { songs ->
                    val strictSongs = songs.filter { it.isStrictSong() }
                    if (strictSongs.isNotEmpty()) {
                        return@withContext Result.success(strictSongs.take(safeLimit))
                    }
                }

                val region = getRegionContext()
                val fallbackQuery = RegionalRecommendationHelper.timeBasedQuery(
                    hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                    countryCode = region.countryCode,
                    countryName = region.countryName
                )
                return@withContext youTubeiService.searchSongs(fallbackQuery, safeLimit)
                    .map { songs -> songs.filter { it.isStrictSong() } }
            }

            // Shuffle and deduplicate
            val uniqueSongs = allSongs.distinctBy { it.id }.shuffled().take(safeLimit)

            Log.d(TAG, "Personalized mix: ${uniqueSongs.size} songs")
            Result.success(uniqueSongs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error getting personalized mix", e)
            Result.failure(e)
        }
    }

    private suspend fun fetchSeedRecommendationsFromInnertube(
        seedSongIds: List<String>,
        limit: Int
    ): List<Song> = coroutineScope {
        if (seedSongIds.isEmpty() || limit <= 0) return@coroutineScope emptyList()

        val selectedSeeds = seedSongIds.take(2)
        val perSeedLimit = (limit / selectedSeeds.size.coerceAtLeast(1))
            .coerceAtLeast(6)
            .coerceAtMost(16)

        selectedSeeds.map { seedId ->
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

    override suspend fun getQueueRecommendations(
        currentSong: Song,
        queueSize: Int
    ): Result<List<Song>> = withContext(Dispatchers.IO) {
        try {
            val topArtists = getTopArtists()
            
            // Build query based on current song and user taste
            val query = when {
                topArtists.contains(currentSong.artist) -> {
                    // User likes this artist, get more from them
                    "${currentSong.artist} songs"
                }
                topArtists.isNotEmpty() -> {
                    // Mix current artist with user's favorites
                    listOf(
                        "${currentSong.artist} similar songs",
                        "songs like ${currentSong.title}",
                        "${topArtists.first()} best songs"
                    ).random()
                }
                else -> {
                    // New user - get similar to current song
                    "songs like ${currentSong.title} ${currentSong.artist}"
                }
            }
            
            Log.d(TAG, "Queue recommendations query: $query")
            
            val result = youTubeiService.searchSongs(query, queueSize + 5)
                .map { songs ->
                    // Filter out current song if present
                    songs
                        .filter { it.isStrictSong() }
                        .filter { it.id != currentSong.id }
                        .take(queueSize)
                }
            
            Log.d(TAG, "Queue recommendations: ${result.getOrNull()?.size ?: 0} songs")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error getting queue recommendations", e)
            Result.failure(e)
        }
    }

    override suspend fun recordSkip(song: Song) {
        Log.d(TAG, "Skip recorded: ${song.title} by ${song.artist}")
        // Skip data is implicitly captured via playDuration < 30s in PlaybackHistoryDao.
        // This method exists for explicit skip-event logging or future analytics.
    }

    override suspend fun getTopGenres(limit: Int): List<String> = withContext(Dispatchers.IO) {
        try {
            val topArtists = getTopArtists()
            val genres = topArtists.flatMap { inferGenresFromArtist(it) }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key }
            Log.d(TAG, "Top genres: $genres")
            genres
        } catch (e: Exception) {
            Log.e(TAG, "Error getting top genres", e)
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PRIVATE HELPER METHODS
    // ═══════════════════════════════════════════════════════════════

    private suspend fun calculateSkipRate(): Float {
        return try {
            val total = playbackHistoryDao.getTotalPlayCount()
            if (total == 0) return 0f
            val skips = playbackHistoryDao.getSkipCount()
            skips.toFloat() / total.toFloat()
        } catch (e: Exception) {
            0f
        }
    }

    /**
     * Infer genre(s) from artist name using a heuristic map.
     * In production, this would be backed by an artist metadata API.
     */
    private fun inferGenresFromArtist(artist: String): List<String> {
        val lower = artist.lowercase()
        return ARTIST_GENRE_MAP.entries
            .filter { (key, _) -> key.lowercase() in lower || lower in key.lowercase() }
            .flatMap { it.value }
            .ifEmpty { listOf("Pop") } // default genre
    }

    /** Lightweight artist → genre mapping for top Indian and international artists */
    private val ARTIST_GENRE_MAP = mapOf(
        "Arijit Singh" to listOf("Bollywood", "Romantic"),
        "Shreya Ghoshal" to listOf("Bollywood", "Classical"),
        "Atif Aslam" to listOf("Bollywood", "Pop"),
        "Pritam" to listOf("Bollywood", "Film"),
        "A.R. Rahman" to listOf("Bollywood", "Classical", "World"),
        "Neha Kakkar" to listOf("Bollywood", "Pop"),
        "Badshah" to listOf("Hip-Hop", "Bollywood"),
        "Honey Singh" to listOf("Hip-Hop", "Bollywood"),
        "Jubin Nautiyal" to listOf("Bollywood", "Pop"),
        "B Praak" to listOf("Bollywood", "Punjabi"),
        "Vishal Mishra" to listOf("Bollywood", "Romantic"),
        "Diljit Dosanjh" to listOf("Punjabi", "Pop"),
        "AP Dhillon" to listOf("Punjabi", "Hip-Hop"),
        "Drake" to listOf("Hip-Hop", "R&B"),
        "Taylor Swift" to listOf("Pop", "Country"),
        "The Weeknd" to listOf("R&B", "Pop"),
        "Ed Sheeran" to listOf("Pop", "Acoustic"),
        "BTS" to listOf("K-Pop", "Pop"),
        "Eminem" to listOf("Hip-Hop", "Rap"),
        "Billie Eilish" to listOf("Pop", "Alternative"),
        "Post Malone" to listOf("Hip-Hop", "Pop"),
        "Dua Lipa" to listOf("Pop", "Dance"),
        "Travis Scott" to listOf("Hip-Hop", "Trap"),
        "Lana Del Rey" to listOf("Indie", "Pop"),
        "Coldplay" to listOf("Rock", "Pop"),
        "Imagine Dragons" to listOf("Rock", "Pop"),
        "Ariana Grande" to listOf("Pop", "R&B"),
        "Justin Bieber" to listOf("Pop", "R&B"),
        "Maroon 5" to listOf("Pop", "Rock"),
        "Sunidhi Chauhan" to listOf("Bollywood", "Pop"),
        "Sonu Nigam" to listOf("Bollywood", "Classical")
    )
    
    private suspend fun getTopArtists(): List<String> {
        return try {
            playbackHistoryDao.getTopArtistsByPlayCount(10)
                .map { it.artist }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun analyzeListeningPattern(): ListeningPattern {
        return try {
            val utcOffsetSeconds = java.util.TimeZone.getDefault().rawOffset / 1000
            val hourlyData = playbackHistoryDao.getPlaybackByHour(utcOffsetSeconds).first()
            if (hourlyData.isEmpty()) return ListeningPattern.MIXED
            
            val peakHour = hourlyData.maxByOrNull { it.count }?.hour ?: 12
            
            when (peakHour) {
                in 5..11 -> ListeningPattern.MORNING_LISTENER
                in 12..16 -> ListeningPattern.AFTERNOON_LISTENER
                in 17..20 -> ListeningPattern.EVENING_LISTENER
                in 21..23, in 0..4 -> ListeningPattern.NIGHT_LISTENER
                else -> ListeningPattern.MIXED
            }
        } catch (e: Exception) {
            ListeningPattern.MIXED
        }
    }

    private suspend fun calculateCompletionRate(): Float {
        return try {
            val stats = playbackHistoryDao.getCompletionStats()
            if (stats.totalCount == 0) return 0.5f
            stats.completedCount.toFloat() / stats.totalCount.toFloat()
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun inferLanguagePreferences(): List<String> {
        return listOf("English")
    }

    private fun buildSearchQueries(
        topArtists: List<String>,
        countryCode: String,
        countryName: String
    ): List<String> {
        val queries = mutableListOf<String>()
        
        // Artist-based queries
        topArtists.take(3).forEach { artist ->
            queries.add("$artist best songs")
        }
        
        // Time-based queries
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        queries.add(
            RegionalRecommendationHelper.timeBasedQuery(
                hour = hour,
                countryCode = countryCode,
                countryName = countryName
            )
        )
        
        // Trending/discovery
        queries.add("trending songs in $countryName ${RegionalRecommendationHelper.currentYear()}")
        queries.add("${RegionalRecommendationHelper.regionalKeywords(countryCode).first()} songs")
        
        return queries
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
        return when (contentType) {
            ContentType.SONG -> true
            ContentType.UNKNOWN -> duration == 0 || duration in 60..600
            else -> false
        }
    }
}
