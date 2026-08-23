package com.sonicmusic.app.domain.usecase

import com.sonicmusic.app.domain.model.ArtistSection
import com.sonicmusic.app.domain.model.ChartSection
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.HomeContent
import com.sonicmusic.app.domain.model.MoodGenre
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
    private val browseRepository: BrowseRepository
) {
    companion object {
        private const val HOME_SECTION_LIMIT = 20
        private const val TOP_ARTIST_SECTION_COUNT = 4
        private const val SECTION_TIMEOUT_MS = 8_000L
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
        var currentContent = HomeContent()

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
                listenAgain = listenAgain,
                recentlyPlayed = recentlyPlayed
            )
            emit(currentContent)
            Log.d("GetHomeContent", "⚡ Stage 1a (Instant DB) emitted in ${System.currentTimeMillis() - startMs}ms")
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

            currentContent = currentContent.copy(
                quickPicks = quickPicks
            )
            emit(currentContent)
            Log.d("GetHomeContent", "⚡ Stage 1b (Quick Picks) emitted in ${System.currentTimeMillis() - startMs}ms")
        }

        // ════════ STAGE 2: Primary Remote Content (Goal: < 800ms) ════════
        coroutineScope {
            val personalizedDeferred = async {
                fetchSongsSection {
                    userTasteRepository.getPersonalizedMix((HOME_SECTION_LIMIT / 2).coerceAtLeast(8))
                }
            }
            val trendingDeferred = async {
                fetchSongsSection { songRepository.getTrending(HOME_SECTION_LIMIT) }
            }
            val newReleasesDeferred = async {
                fetchSongsSection { songRepository.getNewReleases(HOME_SECTION_LIMIT) }
            }
            val mixedForYouDeferred = async {
                fetchSongsSection { browseRepository.getMixedForYou(HOME_SECTION_LIMIT, forceRefresh = forceRefresh) }
            }

            val personalizedRaw = distinctSongs(personalizedDeferred.await(), HOME_SECTION_LIMIT)
            val trendingRaw = distinctSongs(trendingDeferred.await(), HOME_SECTION_LIMIT)
            val newReleasesRaw = distinctSongs(newReleasesDeferred.await(), HOME_SECTION_LIMIT)
            val mixedForYou = distinctSongs(mixedForYouDeferred.await(), HOME_SECTION_LIMIT)

            val personalized = distinctSongs(
                songs = if (personalizedRaw.isNotEmpty()) personalizedRaw else currentContent.quickPicks,
                limit = HOME_SECTION_LIMIT
            )

            currentContent = currentContent.copy(
                personalizedForYou = personalized,
                trending = trendingRaw,
                newReleases = newReleasesRaw,
                mixedForYou = mixedForYou
            )
            emit(currentContent)
            Log.d("GetHomeContent", "⚡ Stage 2 (Primary Network) emitted in ${System.currentTimeMillis() - startMs}ms")
        }

        // ════════ STAGE 3: Secondary & Lazy Content ════════
        coroutineScope {
            val englishHitsDeferred = async {
                fetchSongsSection { songRepository.getEnglishHits(HOME_SECTION_LIMIT * 2) }
            }
            val artistsDeferred = async {
                fetchArtistSection { recommendationRepository.getTopArtistSongs(TOP_ARTIST_SECTION_COUNT) }
            }
            val forgottenDeferred = async {
                fetchSongsSection { recommendationRepository.getForgottenFavorites(HOME_SECTION_LIMIT) }
            }
            val trendingAlbumsDeferred = async {
                fetchSongsSection { browseRepository.getTrendingAlbums(HOME_SECTION_LIMIT, forceRefresh = forceRefresh) }
            }
            val trendingArtistsDeferred = async {
                fetchSongsSection { browseRepository.getTrendingArtists(HOME_SECTION_LIMIT, forceRefresh = forceRefresh) }
            }
            val moodGenresDeferred = async {
                fetchMoodGenres(forceRefresh = forceRefresh)
            }
            val chartsDeferred = async {
                fetchCharts(forceRefresh = forceRefresh)
            }
            val homeShelvesDeferred = async {
                withTimeoutOrNull(SECTION_TIMEOUT_MS) {
                    browseRepository.getHomeShelvesWithContinuation(forceRefresh = forceRefresh).getOrNull()
                }
            }

            val englishHitsRaw = distinctSongs(englishHitsDeferred.await(), HOME_SECTION_LIMIT * 2)
            val artistSectionsRaw = artistsDeferred.await()
            val forgottenRaw = distinctSongs(forgottenDeferred.await(), HOME_SECTION_LIMIT)
            val trendingAlbums = distinctSongs(trendingAlbumsDeferred.await(), HOME_SECTION_LIMIT)
            val trendingArtists = distinctSongs(trendingArtistsDeferred.await(), HOME_SECTION_LIMIT)
            val moodGenres = moodGenresDeferred.await()
            val charts = chartsDeferred.await()
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
                forgottenFavorites = forgottenFavorites,
                englishHits = englishHits,
                artists = artistSections,
                trendingAlbums = trendingAlbums,
                trendingArtists = trendingArtists,
                moodGenres = moodGenres,
                charts = charts,
                continuationShelves = homeShelvesResult?.first.orEmpty(),
                continuationToken = homeShelvesResult?.second
            )
            emit(currentContent)
            Log.d("GetHomeContent", "⚡ Stage 3 (Secondary Network) completed in ${System.currentTimeMillis() - startMs}ms")
        }
    }

    // ══════════════════════════════════════════
    // NEW SECTION FETCHERS
    // ══════════════════════════════════════════

    private suspend fun fetchMoodGenres(forceRefresh: Boolean = false): List<MoodGenre> {
        return withTimeoutOrNull(SECTION_TIMEOUT_MS) {
            browseRepository.getMoodsAndGenres(forceRefresh = forceRefresh).getOrNull().orEmpty()
        }.orEmpty()
    }

    private suspend fun fetchCharts(forceRefresh: Boolean = false): ChartSection? {
        return withTimeoutOrNull(SECTION_TIMEOUT_MS) {
            browseRepository.getCharts(forceRefresh = forceRefresh).getOrNull()
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
                Log.w("GetHomeContent", " Recently played fetch failed", e)
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
            block().getOrNull().orEmpty()
        }.orEmpty()
    }

    private suspend fun fetchArtistSection(
        block: suspend () -> Result<List<ArtistSection>>
    ): List<ArtistSection> {
        return withTimeoutOrNull(SECTION_TIMEOUT_MS) {
            block().getOrNull().orEmpty()
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
            latinRatio >= 0.85f
        }
    }
}
