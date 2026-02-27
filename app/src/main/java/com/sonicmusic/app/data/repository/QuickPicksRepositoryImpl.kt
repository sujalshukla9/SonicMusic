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
import com.sonicmusic.app.domain.usecase.QuickPicksScoringEngine
import com.sonicmusic.app.domain.usecase.QuickPicksScoringEngine.CandidateSource
import com.sonicmusic.app.domain.usecase.QuickPicksScoringEngine.ScoredCandidate
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
 * Quick Picks repository implementation.
 *
 * Pipeline:
 *  1. Build taste signals from [UserTasteRepository] + [PlaybackHistoryDao]
 *  2. Generate familiar candidates from [ListenAgainRepository]
 *  3. Generate discovery candidates from innertube (seed recs + artist search + trending)
 *  4. Score each candidate via [QuickPicksScoringEngine]
 *  5. Filter out anti-preferences (skipped artists, disliked tracks)
 *  6. Assemble with familiar/discovery interleaving + diversity + session shuffle
 *  7. Cache result for 6 hours
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
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours
        private const val FALLBACK_COUNTRY_CODE = "US"
    }

    /** In-memory cache keyed by a session bucket. */
    @Volatile
    private var cachedPicks: List<Song>? = null
    @Volatile
    private var cachedAtMs: Long = 0L
    @Volatile
    private var cachedRegionCode: String? = null

    override suspend fun getQuickPicks(limit: Int): List<Song> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val regionCode = resolveCacheRegionCode()

        // Return cache only if it's fresh for the active region.
        cachedPicks?.takeIf {
            now - cachedAtMs < CACHE_TTL_MS &&
                it.size >= limit / 2 &&
                cachedRegionCode == regionCode
        }?.let {
            Log.d(TAG, "♻️ Returning cached Quick Picks (${it.size} songs)")
            return@withContext it.take(limit)
        }

        val startMs = System.currentTimeMillis()
        try {
            val result = buildQuickPicks(limit)

            // Cache
            cachedPicks = result
            cachedAtMs = now
            cachedRegionCode = regionCode

            Log.d(TAG, "⏱️ Quick Picks built: ${result.size} songs in ${System.currentTimeMillis() - startMs}ms")
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "❌ Quick Picks failed", e)
            // Fallback: return Listen Again + trending
            val fallback = fallbackQuickPicks(limit)
            cachedPicks = fallback
            cachedAtMs = now
            cachedRegionCode = regionCode
            fallback
        }
    }

    // ══════════════════════════════════════════
    // PIPELINE
    // ══════════════════════════════════════════

    private suspend fun buildQuickPicks(limit: Int): List<Song> = coroutineScope {
        val safeLimit = limit.coerceAtLeast(5)

        // ── Step 1: Gather user signals ──
        val tasteProfile = userTasteRepository.getUserTasteProfile()
        val userTopArtists = tasteProfile.topArtists
        val userTopGenres = tasteProfile.topGenres
        val userLanguages = tasteProfile.preferredLanguages

        // ── Step 2: Gather anti-preferences + played IDs in parallel ──
        val skippedArtistsDeferred = async { runCatching { playbackHistoryDao.getSkippedArtists() }.getOrDefault(emptyList()) }
        val playedIdsDeferred = async { runCatching { playbackHistoryDao.getAllPlayedSongIds() }.getOrDefault(emptyList()) }

        val skippedArtists = skippedArtistsDeferred.await().map { it.lowercase().trim() }.toSet()
        val playedSongIds = playedIdsDeferred.await().toSet()

        // ── Step 3: Generate familiar candidates ──
        val familiarDeferred = async { generateFamiliarCandidates(userTopGenres) }

        // ── Step 4: Generate discovery candidates ──
        val discoveryDeferred = async {
            generateDiscoveryCandidates(
                userTopArtists = userTopArtists,
                userTopGenres = userTopGenres,
                playedSongIds = playedSongIds,
                targetCount = safeLimit
            )
        }

        val familiarCandidates = familiarDeferred.await()
        val discoveryCandidates = discoveryDeferred.await()

        Log.d(TAG, "📊 Candidates: ${familiarCandidates.size} familiar, ${discoveryCandidates.size} discovery")

        // ── Step 5: Filter anti-preferences ──
        val allCandidates = (familiarCandidates + discoveryCandidates)
            .filter { c -> c.song.artist.lowercase().trim() !in skippedArtists }

        // ── Step 6: Score all candidates ──
        val scored = allCandidates.map { candidate ->
            candidate.copy(
                finalScore = QuickPicksScoringEngine.scoreCandidate(
                    candidate = candidate,
                    userTopGenres = userTopGenres,
                    userTopArtists = userTopArtists,
                    userLanguages = userLanguages
                )
            )
        }

        // ── Step 7: Assemble with interleaving + diversity + shuffle ──
        val sessionSeed = System.currentTimeMillis() / CACHE_TTL_MS // changes every 6h
        QuickPicksScoringEngine.assemble(
            candidates = scored,
            targetCount = safeLimit,
            sessionSeed = sessionSeed
        )
    }

    // ══════════════════════════════════════════
    // FAMILIAR CANDIDATES (from Listen Again pool)
    // ══════════════════════════════════════════

    private suspend fun generateFamiliarCandidates(
        userTopGenres: List<String>
    ): List<ScoredCandidate> {
        return try {
            val listenAgainSongs = listenAgainRepository.getListenAgainSongs(50)

            listenAgainSongs.mapIndexed { index, song ->
                // Source score decreases by rank in Listen Again
                val sourceScore = (1.0 - index.toDouble() / listenAgainSongs.size.coerceAtLeast(1))
                    .coerceIn(0.2, 1.0)

                val genre = inferGenreForArtist(song.artist, userTopGenres)

                ScoredCandidate(
                    song = song,
                    source = CandidateSource.FAMILIAR,
                    sourceScore = sourceScore,
                    isFamiliar = true,
                    inferredGenre = genre
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating familiar candidates", e)
            emptyList()
        }
    }

    // ══════════════════════════════════════════
    // DISCOVERY CANDIDATES (from innertube + search)
    // ══════════════════════════════════════════

    private suspend fun generateDiscoveryCandidates(
        userTopArtists: List<String>,
        userTopGenres: List<String>,
        playedSongIds: Set<String>,
        targetCount: Int
    ): List<ScoredCandidate> = coroutineScope {
        val candidates = mutableListOf<ScoredCandidate>()

        // ── Source A: Unplayed tracks from top artists ──
        val artistDeepCutsDeferred = async {
            fetchArtistDeepCuts(
                topArtists = userTopArtists.take(5),
                playedSongIds = playedSongIds,
                userTopGenres = userTopGenres
            )
        }

        // ── Source B: Innertube seed recommendations ──
        val seedRecsDeferred = async {
            fetchSeedRecommendations(
                playedSongIds = playedSongIds,
                userTopGenres = userTopGenres,
                limit = targetCount
            )
        }

        // ── Source C: Trending in user's genres ──
        val trendingDeferred = async {
            fetchTrendingInGenres(
                playedSongIds = playedSongIds,
                userTopGenres = userTopGenres
            )
        }

        candidates.addAll(artistDeepCutsDeferred.await())
        candidates.addAll(seedRecsDeferred.await())
        candidates.addAll(trendingDeferred.await())

        // Deduplicate by song ID
        candidates
            .distinctBy { it.song.id }
            .filter { it.song.id !in playedSongIds }
    }

    /**
     * Source A: Search for unplayed songs by the user's top artists.
     */
    private suspend fun fetchArtistDeepCuts(
        topArtists: List<String>,
        playedSongIds: Set<String>,
        userTopGenres: List<String>
    ): List<ScoredCandidate> = coroutineScope {
        if (topArtists.isEmpty()) return@coroutineScope emptyList()

        topArtists.take(3).mapIndexed { artistRank, artist ->
            async {
                try {
                    val songs = youTubeiService.searchSongs("$artist songs", 10)
                        .getOrNull()
                        .orEmpty()
                        .filter { it.isStrictSong() }
                        .filter { it.id !in playedSongIds }

                    songs.map { song ->
                        val sourceScore = (1.0 - artistRank.toDouble() / 5.0) *
                                ((song.viewCount ?: 50000L).toDouble() / 100000.0).coerceAtMost(1.0)

                        ScoredCandidate(
                            song = song,
                            source = CandidateSource.SAME_ARTIST_UNPLAYED,
                            sourceScore = sourceScore.coerceIn(0.0, 1.0),
                            isFamiliar = false,
                            inferredGenre = inferGenreForArtist(song.artist, userTopGenres)
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching deep cuts for $artist", e)
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    /**
     * Source B: Get innertube recommendations from seed songs.
     */
    private suspend fun fetchSeedRecommendations(
        playedSongIds: Set<String>,
        userTopGenres: List<String>,
        limit: Int
    ): List<ScoredCandidate> = coroutineScope {
        try {
            val recentIds = playbackHistoryDao.getRecentSongIds(8)
            if (recentIds.isEmpty()) return@coroutineScope emptyList()

            val seedIds = recentIds.take(2)
            seedIds.map { seedId ->
                async {
                    youTubeiService.getSongRecommendationsFromInnertube(
                        videoId = seedId,
                        limit = (limit / seedIds.size).coerceAtLeast(6).coerceAtMost(16)
                    ).getOrNull()
                        .orEmpty()
                        .filter { it.isStrictSong() }
                        .filter { it.id !in playedSongIds }
                        .map { song ->
                            ScoredCandidate(
                                song = song,
                                source = CandidateSource.SIMILAR_ARTIST,
                                sourceScore = ((song.viewCount ?: 30000L).toDouble() / 100000.0)
                                    .coerceIn(0.2, 0.9),
                                isFamiliar = false,
                                inferredGenre = inferGenreForArtist(song.artist, userTopGenres)
                            )
                        }
                }
            }.awaitAll().flatten()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching seed recommendations", e)
            emptyList()
        }
    }

    /**
     * Source C: Trending songs filtered to user's preferred genres.
     */
    private suspend fun fetchTrendingInGenres(
        playedSongIds: Set<String>,
        userTopGenres: List<String>
    ): List<ScoredCandidate> {
        return try {
            val trending = youTubeiService.getTrendingSongs(20)
                .getOrNull()
                .orEmpty()
                .filter { it.isStrictSong() }
                .filter { it.id !in playedSongIds }

            trending.map { song ->
                ScoredCandidate(
                    song = song,
                    source = CandidateSource.TRENDING_GENRE,
                    sourceScore = 0.6, // moderate base
                    isFamiliar = false,
                    inferredGenre = inferGenreForArtist(song.artist, userTopGenres)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching trending songs", e)
            emptyList()
        }
    }

    // ══════════════════════════════════════════
    // FALLBACK
    // ══════════════════════════════════════════

    /**
     * Emergency fallback when the pipeline fails entirely.
     */
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

    // ══════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════

    /**
     * Infer genre for an artist based on user's top genres heuristic.
     * Returns the first matching genre or "Pop" default.
     */
    private fun inferGenreForArtist(artist: String, userTopGenres: List<String>): String {
        val lower = artist.lowercase()
        for ((key, genres) in ARTIST_GENRE_MAP) {
            if (key.lowercase() in lower || lower in key.lowercase()) {
                return genres.firstOrNull() ?: "Pop"
            }
        }
        return userTopGenres.firstOrNull() ?: "Pop"
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

    /** Same artist → genre map used in UserTasteRepositoryImpl. */
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
}
