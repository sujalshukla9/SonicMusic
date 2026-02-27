package com.sonicmusic.app.domain.usecase

import com.sonicmusic.app.domain.model.ArtistSection
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.HomeContent
import com.sonicmusic.app.domain.model.Song
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

class GetHomeContentUseCase @Inject constructor(
    private val songRepository: SongRepository,
    private val historyRepository: HistoryRepository,
    private val recommendationRepository: RecommendationRepository,
    private val userTasteRepository: UserTasteRepository,
    private val listenAgainRepository: ListenAgainRepository,
    private val quickPicksRepository: QuickPicksRepository
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

    suspend operator fun invoke(): Result<HomeContent> {
        val startMs = System.currentTimeMillis()
        return try {
            coroutineScope {
                val listenAgainDeferred = async {
                    runCatching {
                        listenAgainRepository.getListenAgainSongs(HOME_SECTION_LIMIT)
                    }.getOrDefault(emptyList())
                }

                val quickPicksDeferred = async {
                    try {
                        // Use the new Quick Picks pipeline
                        val picks = quickPicksRepository.getQuickPicks(HOME_SECTION_LIMIT)
                        if (picks.isNotEmpty()) picks
                        else fetchSongsSection { recommendationRepository.getQuickPicks(HOME_SECTION_LIMIT) }
                    } catch (e: Exception) {
                        // Fallback to old recommendation repo
                        fetchSongsSection { recommendationRepository.getQuickPicks(HOME_SECTION_LIMIT) }
                    }
                }
                val newReleasesDeferred = async {
                    fetchSongsSection { songRepository.getNewReleases(HOME_SECTION_LIMIT) }
                }
                val trendingDeferred = async {
                    fetchSongsSection { songRepository.getTrending(HOME_SECTION_LIMIT) }
                }
                val englishHitsDeferred = async {
                    fetchSongsSection { songRepository.getEnglishHits(HOME_SECTION_LIMIT * 2) }
                }
                val artistsDeferred = async {
                    fetchArtistSection { recommendationRepository.getTopArtistSongs(TOP_ARTIST_SECTION_COUNT) }
                }
                val personalizedDeferred = async {
                    fetchSongsSection {
                        userTasteRepository.getPersonalizedMix((HOME_SECTION_LIMIT / 2).coerceAtLeast(8))
                    }
                }
                val forgottenDeferred = async {
                    fetchSongsSection { recommendationRepository.getForgottenFavorites(HOME_SECTION_LIMIT) }
                }

                val listenAgain = distinctSongs(listenAgainDeferred.await(), HOME_SECTION_LIMIT)
                val quickPicksRaw = distinctSongs(quickPicksDeferred.await(), HOME_SECTION_LIMIT)
                val newReleasesRaw = distinctSongs(newReleasesDeferred.await(), HOME_SECTION_LIMIT)
                val trendingRaw = distinctSongs(trendingDeferred.await(), HOME_SECTION_LIMIT)
                val englishHitsRaw = distinctSongs(englishHitsDeferred.await(), HOME_SECTION_LIMIT * 2)
                val personalizedRaw = distinctSongs(personalizedDeferred.await(), HOME_SECTION_LIMIT)
                val forgottenRaw = distinctSongs(forgottenDeferred.await(), HOME_SECTION_LIMIT)
                val artistSectionsRaw = artistsDeferred.await()

                val quickPicks = distinctSongs(
                    songs = if (quickPicksRaw.isNotEmpty()) {
                        quickPicksRaw
                    } else {
                        trendingRaw + newReleasesRaw + listenAgain
                    },
                    limit = HOME_SECTION_LIMIT
                )

                val personalized = distinctSongs(
                    songs = if (personalizedRaw.isNotEmpty()) personalizedRaw else quickPicks,
                    limit = HOME_SECTION_LIMIT
                )

                val englishHits = distinctSongs(
                    songs = filterLikelyEnglishSongs(englishHitsRaw),
                    limit = HOME_SECTION_LIMIT
                )

                val listenAgainIds = listenAgain.asSequence().map { it.id }.toHashSet()
                val forgottenFavorites = distinctSongs(
                    songs = forgottenRaw.filterNot { it.id in listenAgainIds },
                    limit = HOME_SECTION_LIMIT
                )

                val artistSections = normalizeArtistSections(artistSectionsRaw)

                Result.success(
                    HomeContent(
                        listenAgain = listenAgain,
                        quickPicks = quickPicks,
                        forgottenFavorites = forgottenFavorites,
                        newReleases = newReleasesRaw,
                        trending = trendingRaw,
                        englishHits = englishHits,
                        artists = artistSections,
                        personalizedForYou = personalized
                    )
                )
            }.also {
                Log.d("GetHomeContent", "⏱️ Home content fetched in ${System.currentTimeMillis() - startMs}ms")
            }
        } catch (e: Exception) {
            Log.e("GetHomeContent", "❌ Home content failed in ${System.currentTimeMillis() - startMs}ms", e)
            Result.failure(e)
        }
    }

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
