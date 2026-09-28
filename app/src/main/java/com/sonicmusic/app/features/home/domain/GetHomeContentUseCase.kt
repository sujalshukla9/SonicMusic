package com.sonicmusic.app.features.home.domain

import com.sonicmusic.app.domain.model.ArtistSection
import com.sonicmusic.app.domain.model.ChartSection
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.HomeContent
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.BrowseRepository
import com.sonicmusic.app.domain.repository.HistoryRepository
import com.sonicmusic.app.domain.repository.ListenAgainRepository
import com.sonicmusic.app.domain.repository.QuickPicksRepository
import com.sonicmusic.app.domain.repository.RecommendationRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.domain.repository.UserTasteRepository
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class GetHomeContentUseCase @Inject constructor(
    private val songRepository: SongRepository,
    private val historyRepository: HistoryRepository,
    private val recommendationRepository: RecommendationRepository,
    private val userTasteRepository: UserTasteRepository,
    private val listenAgainRepository: ListenAgainRepository,
    private val quickPicksRepository: QuickPicksRepository,
    private val browseRepository: BrowseRepository,
    private val homeCacheDataSource: com.sonicmusic.app.features.home.data.HomeCacheDataSource
) {
    companion object {
        private const val HOME_SECTION_LIMIT = 20
        private const val TOP_ARTIST_SECTION_COUNT = 4
        private const val SECTION_TIMEOUT_MS = 6_000L
        private const val ARTIST_SECTION_SONG_LIMIT = 10
        private val NON_ENGLISH_LANGUAGE_MARKERS = setOf(
            "hindi",
            "punjabi",
            "tamil",
            "telugu",
            "malayalam",
            "kannada",
            "marathi",
            "bhojpuri",
            "gujarati",
            "bangla",
            "bengali"
        )
    }

    operator fun invoke(forceRefresh: Boolean = false): Flow<HomeContent> = flow {
        val startMs = System.currentTimeMillis()

        // ════════ STAGE 0: Instant Memory & Disk Cache (Goal: < 15ms) ════════
        var currentContent = homeCacheDataSource.getCachedHome(ignoreTtl = true) ?: HomeContent()
        if (currentContent.hasAnyContent()) {
            emit(currentContent)
            Log.d("GetHomeContent", "Stage 0 (Instant Cache Hit) emitted in ${System.currentTimeMillis() - startMs}ms")
            val isFresh = homeCacheDataSource.isCacheValid()
            if (!forceRefresh && isFresh) {
                Log.d("GetHomeContent", "Home cache is fresh within TTL, skipping background refresh")
                return@flow
            }
        }

        // ════════ STAGE 1a: Instant Local DB (Goal: < 50ms) ════════
        coroutineScope {
            val listenAgainDeferred = async {
                runCatching {
                    listenAgainRepository.getListenAgainSongs(HOME_SECTION_LIMIT)
                }.getOrDefault(emptyList())
            }
            val recentlyPlayedDeferred = async {
                fetchRecentlyPlayed()
            }

            val listenAgain = distinctSongs(listenAgainDeferred.await(), HOME_SECTION_LIMIT)
            val recentlyPlayed = distinctSongs(recentlyPlayedDeferred.await(), HOME_SECTION_LIMIT)

            currentContent = currentContent.copy(
                listenAgain = if (listenAgain.isNotEmpty()) listenAgain else currentContent.listenAgain,
                recentlyPlayed = if (recentlyPlayed.isNotEmpty()) recentlyPlayed else currentContent.recentlyPlayed
            )
            emit(currentContent)
            Log.d("GetHomeContent", "Stage 1a (Instant DB) emitted in ${System.currentTimeMillis() - startMs}ms")
        }

        // ════════ STAGE 1b: Quick Picks (Goal: < 150ms if cached) ════════
        coroutineScope {
            val quickPicksDeferred = async {
                try {
                    quickPicksRepository.getQuickPicks(HOME_SECTION_LIMIT, forceRefresh = forceRefresh)
                } catch (e: Exception) {
                    emptyList()
                }
            }

            val quickPicksRaw = distinctSongs(quickPicksDeferred.await(), HOME_SECTION_LIMIT)
            val quickPicks = distinctSongs(
                songs = if (quickPicksRaw.isNotEmpty()) quickPicksRaw else currentContent.listenAgain,
                limit = HOME_SECTION_LIMIT
            )

            if (quickPicks.isNotEmpty()) {
                currentContent = currentContent.copy(quickPicks = quickPicks)
                emit(currentContent)
            }
            Log.d("GetHomeContent", "Stage 1b (Quick Picks) emitted in ${System.currentTimeMillis() - startMs}ms")
        }

        // ════════ STAGE 2: Primary Remote Content (Mixed for You from InnerTube) ════════
        coroutineScope {
            val mixedForYouDeferred = async {
                fetchSongsSection { browseRepository.getMixedForYou(HOME_SECTION_LIMIT, forceRefresh = forceRefresh) }
            }

            val mixedForYou = distinctSongs(mixedForYouDeferred.await(), HOME_SECTION_LIMIT)

            if (mixedForYou.isNotEmpty()) {
                currentContent = currentContent.copy(mixedForYou = mixedForYou)
                emit(currentContent)
            }
            Log.d("GetHomeContent", "Stage 2 (Primary Network) emitted in ${System.currentTimeMillis() - startMs}ms")
        }

        // ════════ STAGE 3: Secondary Shelves (Batched to prevent socket congestion) ════════
        coroutineScope {
            // Batch 3a: High-interest discovery
            val trendingAlbumsDeferred = async {
                fetchSongsSection { browseRepository.getTrendingAlbums(HOME_SECTION_LIMIT, forceRefresh = forceRefresh) }
            }
            val chartsDeferred = async {
                fetchCharts(forceRefresh = forceRefresh)
            }
            val englishHitsDeferred = async {
                fetchSongsSection { songRepository.getEnglishHits(HOME_SECTION_LIMIT * 2) }
            }

            // Batch 3b: Lower-priority / lazy shelves
            val artistsDeferred = async {
                fetchArtistSection { recommendationRepository.getTopArtistSongs(TOP_ARTIST_SECTION_COUNT) }
            }
            val forgottenDeferred = async {
                fetchSongsSection { recommendationRepository.getForgottenFavorites(HOME_SECTION_LIMIT) }
            }
            val homeShelvesDeferred = async {
                withTimeoutOrNull(SECTION_TIMEOUT_MS) {
                    browseRepository.getHomeShelvesWithContinuation(forceRefresh = forceRefresh).getOrNull()
                }
            }

            val trendingAlbums = distinctSongs(trendingAlbumsDeferred.await(), HOME_SECTION_LIMIT)
            val charts = chartsDeferred.await()
            val englishHitsRaw = distinctSongs(englishHitsDeferred.await(), HOME_SECTION_LIMIT * 2)
            val artistSectionsRaw = artistsDeferred.await()
            val forgottenRaw = distinctSongs(forgottenDeferred.await(), HOME_SECTION_LIMIT)
            val homeShelvesResult = homeShelvesDeferred.await()

            val englishHits = distinctSongs(
                songs = filterLikelyEnglishSongs(englishHitsRaw),
                limit = HOME_SECTION_LIMIT
            )

            val listenAgainIds = currentContent.listenAgain.asSequence().map { it.id }.toHashSet()
            val forgottenFavorites = distinctSongs(
                songs = forgottenRaw.filterNot { it.id in listenAgainIds },
                limit = HOME_SECTION_LIMIT
            )

            val artistSections = normalizeArtistSections(artistSectionsRaw)

            currentContent = currentContent.copy(
                trendingAlbums = if (trendingAlbums.isNotEmpty()) trendingAlbums else currentContent.trendingAlbums,
                charts = charts ?: currentContent.charts,
                englishHits = if (englishHits.isNotEmpty()) englishHits else currentContent.englishHits,
                artists = if (artistSections.isNotEmpty()) artistSections else currentContent.artists,
                forgottenFavorites = if (forgottenFavorites.isNotEmpty()) forgottenFavorites else currentContent.forgottenFavorites,
                continuationShelves = if (homeShelvesResult?.first?.isNotEmpty() == true) homeShelvesResult.first else currentContent.continuationShelves,
                continuationToken = homeShelvesResult?.second ?: currentContent.continuationToken
            )
            emit(currentContent)

            // Cache the full normalized snapshot to Memory + Room Disk Cache
            homeCacheDataSource.saveHomeCache(currentContent)
            Log.d("GetHomeContent", "Stage 3 (Secondary Network & Cache Persist) completed in ${System.currentTimeMillis() - startMs}ms")
        }
    }



    private suspend fun fetchCharts(forceRefresh: Boolean = false): ChartSection? {
        return withTimeoutOrNull(SECTION_TIMEOUT_MS) {
            runCatching {
                browseRepository.getCharts(forceRefresh = forceRefresh).getOrNull()
            }.getOrNull()
        }
    }

    private suspend fun fetchRecentlyPlayed(): List<Song> {
        return withTimeoutOrNull(SECTION_TIMEOUT_MS) {
            try {
                historyRepository.getRecentlyPlayedSongs(HOME_SECTION_LIMIT)
                    .first()
                    .map { history ->
                        Song(
                            id = history.songId,
                            title = history.title,
                            artist = history.artist,
                            duration = 0,
                            thumbnailUrl = history.thumbnailUrl
                        )
                    }
            } catch (e: Exception) {
                Log.w("GetHomeContent", "Recently played fetch failed", e)
                emptyList()
            }
        }.orEmpty()
    }

    // ══════════════════════════════════════════
    // EXISTING HELPERS
    // ══════════════════════════════════════════

    private suspend fun fetchSongsSection(
        block: suspend () -> Result<List<Song>>
    ): List<Song> {
        return withTimeoutOrNull(SECTION_TIMEOUT_MS) {
            runCatching {
                block().getOrNull().orEmpty()
            }.getOrDefault(emptyList())
        }.orEmpty()
    }

    private suspend fun fetchArtistSection(
        block: suspend () -> Result<List<ArtistSection>>
    ): List<ArtistSection> {
        return withTimeoutOrNull(SECTION_TIMEOUT_MS) {
            runCatching {
                block().getOrNull().orEmpty()
            }.getOrDefault(emptyList())
        }.orEmpty()
    }

    private fun normalizeArtistSections(sections: List<ArtistSection>): List<ArtistSection> {
        return sections
            .asSequence()
            .map { section ->
                section.copy(
                    songs = distinctSongs(
                        songs = section.songs,
                        limit = ARTIST_SECTION_SONG_LIMIT
                    )
                )
            }
            .filter { section -> section.songs.isNotEmpty() }
            .distinctBy { section ->
                section.artist.id.ifBlank {
                    section.artist.name.lowercase()
                }
            }
            .take(TOP_ARTIST_SECTION_COUNT)
            .toList()
    }

    private fun distinctSongs(songs: List<Song>, limit: Int): List<Song> {
        return songs
            .asSequence()
            .filter { song -> song.id.isNotBlank() && song.title.isNotBlank() }
            .distinctBy { song -> song.id }
            .take(limit)
            .toList()
    }

    private fun filterLikelyEnglishSongs(songs: List<Song>): List<Song> {
        return songs.filter { song ->
            val text = "${song.title} ${song.artist}".trim()
            if (text.isBlank()) return@filter false
            if (song.contentType !in setOf(ContentType.SONG, ContentType.UNKNOWN)) {
                return@filter false
            }

            val lowercaseText = text.lowercase()
            if (NON_ENGLISH_LANGUAGE_MARKERS.any { marker -> lowercaseText.contains(marker) }) {
                return@filter false
            }

            val letters = text.filter { it.isLetter() }
            if (letters.isEmpty()) return@filter false

            val latinLetters = letters.count {
                Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN
            }
            val latinRatio = latinLetters.toFloat() / letters.length.toFloat()
            latinRatio >= 0.70f
        }
    }
}
