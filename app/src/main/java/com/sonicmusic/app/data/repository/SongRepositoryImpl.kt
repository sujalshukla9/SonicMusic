package com.sonicmusic.app.data.repository

import android.util.Log
import com.sonicmusic.app.features.downloads.data.SongDownloadManager
import com.sonicmusic.app.data.local.dao.FollowedArtistDao
import com.sonicmusic.app.data.local.dao.PlaybackHistoryDao
import com.sonicmusic.app.data.local.dao.SongDao
import com.sonicmusic.app.data.local.entity.SongEntity
import com.sonicmusic.app.data.remote.source.AudioStreamExtractor
import com.sonicmusic.app.data.remote.source.NewPipeService
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.AudioStreamInfo
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.domain.repository.UserTasteRepository
import com.sonicmusic.app.features.recommendations.engine.NewReleaseScoringEngine
import com.sonicmusic.app.features.recommendations.engine.TrendingScoringEngine
import com.sonicmusic.app.features.player.audio.AudioEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.sonicmusic.app.data.mapper.toEntity
import com.sonicmusic.app.data.mapper.toSong
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SongRepositoryImpl @Inject constructor(
    private val songDao: SongDao,
    private val youTubeiService: YouTubeiService,
    private val newPipeService: NewPipeService,
    private val audioStreamExtractor: AudioStreamExtractor,
    private val songDownloadManager: SongDownloadManager,
    private val audioEngine: AudioEngine,
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val followedArtistDao: FollowedArtistDao,
    private val userTasteRepository: UserTasteRepository,
    private val songSourceResolver: com.sonicmusic.app.data.repository.SongSourceResolver,
) : SongRepository {

    override suspend fun getSongStreamSource(videoId: String): com.sonicmusic.app.data.repository.SongSource {
        return songSourceResolver.getSongStreamSource(videoId)
    }
    
    companion object {
        private const val TAG = "SongRepo"
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

    // Keep quality awareness for in-memory URL cache validity.
    private val streamQualityCache = ConcurrentHashMap<String, StreamQuality>()
    
    override suspend fun searchSongs(query: String, limit: Int): Result<List<Song>> {
        return youTubeiService.searchSongs(query, limit)
            .onSuccess { songs ->
                // Cache songs to database
                val entities = songs.map { it.toEntity() }
                songDao.insertAll(entities)
            }
    }

    override suspend fun getSearchSuggestions(query: String): Result<List<String>> {
        return youTubeiService.getSearchSuggestions(query)
    }

    override suspend fun getSongById(id: String): Result<Song> {
        // Try to get from database first
        val cachedSong = songDao.getSongById(id)
        if (cachedSong != null && cachedSong.title.isNotBlank() && cachedSong.artist.isNotBlank() && !cachedSong.albumId.isNullOrBlank()) {
            return Result.success(cachedSong.toSong())
        }
        if (cachedSong != null && (cachedSong.title.isBlank() || cachedSong.artist.isBlank())) {
            songDao.deleteSong(id)
        }

        // Fetch from API
        return youTubeiService.getSongDetails(id)
            .map { song ->
                if (song.albumBrowseId.isNullOrBlank()) {
                    try {
                        val upNextSongs = youTubeiService.getUpNext(id).getOrNull()?.first.orEmpty()
                        val matching = upNextSongs.firstOrNull { it.id == id }
                        if (matching != null && !matching.albumBrowseId.isNullOrBlank()) {
                            song.copy(
                                album = matching.album,
                                albumId = matching.albumId,
                                albumBrowseId = matching.albumBrowseId,
                                albumName = matching.albumName
                            )
                        } else if (upNextSongs.isNotEmpty() && upNextSongs[0].id == id && !upNextSongs[0].albumBrowseId.isNullOrBlank()) {
                            val firstSong = upNextSongs[0]
                            song.copy(
                                album = firstSong.album,
                                albumId = firstSong.albumId,
                                albumBrowseId = firstSong.albumBrowseId,
                                albumName = firstSong.albumName
                            )
                        } else {
                            song
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to resolve album info from Up Next for $id", e)
                        song
                    }
                } else {
                    song
                }
            }
            .onSuccess { song ->
                val entityToInsert = if (cachedSong != null && cachedSong.title.isNotBlank()) {
                    song.toEntity().copy(
                        isLiked = cachedSong.isLiked,
                        likedAt = cachedSong.likedAt,
                        cachedStreamUrl = cachedSong.cachedStreamUrl,
                        cacheExpiry = cachedSong.cacheExpiry
                    )
                } else {
                    song.toEntity()
                }
                songDao.insertSong(entityToInsert)
            }
    }

    override suspend fun getStreamUrl(songId: String, quality: StreamQuality): Result<String> {
        return getStreamWithInfo(songId, quality).map { it.first }
    }

    override suspend fun getStreamWithInfo(
        songId: String,
        quality: StreamQuality,
    ): Result<Pair<String, AudioStreamInfo>> {
        val effectiveQuality = resolveQuality(quality)

        // Prefer offline file when available.
        songDownloadManager.getPlaybackFile(songId).getOrNull()?.let { localFile ->
            val localUri = localFile.absolutePath
            val localInfo = fallbackStreamInfo(
                quality = StreamQuality.BEST,
                codec = "LOCAL",
                container = localFile.extension.ifBlank { "File" },
            )
            audioEngine.updateStreamInfo(localInfo)
            return Result.success(localUri to localInfo)
        }

        val cachedSong = songDao.getSongById(songId)?.takeIf { it.title.isNotBlank() && it.artist.isNotBlank() }
        if (cachedSong?.cachedStreamUrl != null &&
            cachedSong.cacheExpiry != null &&
            cachedSong.cacheExpiry > System.currentTimeMillis() &&
            streamQualityCache[songId] == effectiveQuality
        ) {
            // Confirm the cached URL's own InnerTube rendition. Do not inherit a
            // video-level result from a different quality or codec.
            val hasAtmos = audioStreamExtractor.detectDolbyAtmos(songId, cachedSong.cachedStreamUrl)
            val cachedInfo = fallbackStreamInfo(quality = effectiveQuality).copy(hasDolbyAtmos = hasAtmos)
            audioEngine.updateStreamInfo(cachedInfo)
            return Result.success(cachedSong.cachedStreamUrl to cachedInfo)
        }

        val (streamUrl, info) = audioStreamExtractor.extractAudioStream(songId, effectiveQuality)
            .getOrElse { return Result.failure(it) }

        streamQualityCache[songId] = effectiveQuality

        val expiry = System.currentTimeMillis() + (30 * 60 * 1000)
        if (cachedSong != null) {
            songDao.updateSong(
                cachedSong.copy(
                    cachedStreamUrl = streamUrl,
                    cacheExpiry = expiry,
                )
            )
        }

        audioEngine.updateStreamInfo(info)
        return Result.success(streamUrl to info)
    }

    override suspend fun getArtistSongs(artistName: String, limit: Int): Result<List<Song>> {
        val safeLimit = limit.coerceAtLeast(1).coerceAtMost(600)
        val searchWindow = (safeLimit * 2).coerceAtLeast(safeLimit).coerceAtMost(500)
        val primaryResult = youTubeiService.searchSongs(artistName, searchWindow)
        val result = if (!primaryResult.getOrNull().isNullOrEmpty()) {
            primaryResult
        } else {
            newPipeService.getArtistSongs(artistName, safeLimit).recoverCatching {
                primaryResult.getOrThrow()
            }
        }

        return result
            .map { songs ->
                val normalizedArtist = normalizeArtistName(artistName)
                songs.filter { song -> isArtistMatch(song.artist, normalizedArtist) }
                    .distinctBy { it.id }
                    .take(safeLimit)
            }
            .onSuccess { songs ->
                val entities = songs.map { it.toEntity() }
                songDao.insertAll(entities)
            }
    }

    override suspend fun getNewReleases(limit: Int): Result<List<Song>> {
        return try {
            val rawReleases = youTubeiService.getNewReleases(limit)
                .getOrNull().orEmpty()
            Log.d(TAG, "New Releases directly from YTM: ${rawReleases.size} songs")
            Result.success(rawReleases.take(limit))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "New releases fetch failed", e)
            Result.success(emptyList())
        }
    }

    override suspend fun getTrending(limit: Int): Result<List<Song>> {
        return try {
            val rawTrending = youTubeiService.getTrendingSongs(limit)
                .getOrNull().orEmpty()
            Log.d(TAG, "Trending directly from YTM: ${rawTrending.size} songs")
            Result.success(rawTrending.take(limit))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Trending fetch failed", e)
            Result.success(emptyList())
        }
    }

    override suspend fun getEnglishHits(limit: Int): Result<List<Song>> {
        return try {
            val safeLimit = limit.coerceAtLeast(1).coerceAtMost(200)
            val hits = youTubeiService.getEnglishHits(safeLimit)
                .getOrNull().orEmpty()
                .filter(::isLikelyEnglishTrack)

            if (hits.isNotEmpty()) {
                Log.d(TAG, "English hits directly from YTM: ${hits.size} songs")
                Result.success(hits.take(safeLimit))
            } else {
                val year = Calendar.getInstance().get(Calendar.YEAR)
                val fallbackSongs = youTubeiService.searchSongs("top english songs $year", safeLimit)
                    .getOrNull().orEmpty()
                    .filter(::isLikelyEnglishTrack)
                Result.success(fallbackSongs.take(safeLimit))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "English hits fetch failed", e)
            Result.success(emptyList())
        }
    }

    override suspend fun likeSong(song: Song) {
        val existingSong = songDao.getSongById(song.id)
        if (existingSong == null) {
            // Song doesn't exist, insert it with isLiked = true
            songDao.insertSong(song.toEntity().copy(isLiked = true, likedAt = System.currentTimeMillis()))
        } else {
            // Song exists, just update like status
            songDao.updateLikeStatus(song.id, true, System.currentTimeMillis())
        }
    }

    override suspend fun unlikeSong(songId: String) {
        songDao.updateLikeStatus(songId, false, null)
    }

    override fun getLikedSongs(): Flow<List<Song>> {
        return songDao.getLikedSongs().map { entities ->
            entities.map { it.toSong() }
        }
    }

    override suspend fun clearCachedStreamUrl(songId: String) {
        val cachedSong = songDao.getSongById(songId)
        cachedSong?.let {
            songDao.updateSong(it.copy(cachedStreamUrl = null, cacheExpiry = null))
        }
        streamQualityCache.remove(songId)
    }

    override suspend fun cacheSong(song: Song) {
        val existing = songDao.getSongById(song.id)
        val entity = song.toEntity().let { base ->
            if (existing != null) {
                base.copy(
                    isLiked = existing.isLiked,
                    likedAt = existing.likedAt,
                    cachedStreamUrl = existing.cachedStreamUrl,
                    cacheExpiry = existing.cacheExpiry
                )
            } else {
                base
            }
        }
        songDao.insertSong(entity)
    }

    override suspend fun isLiked(songId: String): Boolean {
        return songDao.isLiked(songId) ?: false
    }

    override fun observeIsLiked(songId: String): kotlinx.coroutines.flow.Flow<Boolean> {
        return songDao.observeIsLiked(songId).map { it ?: false }
    }

    private fun resolveQuality(requested: StreamQuality): StreamQuality {
        return if (requested == StreamQuality.BEST) {
            audioEngine.getOptimalQuality()
        } else {
            requested
        }
    }

    private fun normalizeArtistName(value: String): String {
        return value.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isArtistMatch(candidateArtist: String, normalizedQuery: String): Boolean {
        if (normalizedQuery.isBlank()) return true
        val normalizedCandidate = normalizeArtistName(candidateArtist)
        if (normalizedCandidate.isBlank()) return false
        if (normalizedCandidate.contains(normalizedQuery) || normalizedQuery.contains(normalizedCandidate)) {
            return true
        }

        val queryTokens = normalizedQuery.split(" ").filter { it.length >= 3 }.toSet()
        val candidateTokens = normalizedCandidate.split(" ").filter { it.length >= 3 }.toSet()
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) return false
        return queryTokens.intersect(candidateTokens).isNotEmpty()
    }

    private fun isLikelyEnglishTrack(song: Song): Boolean {
        val text = "${song.title} ${song.artist}".trim()
        if (text.isBlank()) return false
        if (song.contentType !in setOf(ContentType.SONG, ContentType.UNKNOWN)) return false

        val lowercaseText = text.lowercase(Locale.ROOT)
        if (NON_ENGLISH_LANGUAGE_MARKERS.any { marker -> lowercaseText.contains(marker) }) {
            return false
        }

        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false

        val latinCount = letters.count {
            Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN
        }
        val latinRatio = latinCount.toFloat() / letters.length.toFloat()
        return latinRatio >= 0.85f
    }

    private fun fallbackStreamInfo(
        quality: StreamQuality,
        codec: String? = null,
        container: String? = null
    ): AudioStreamInfo {
        val bitrate = when (quality) {
            StreamQuality.BEST -> 256
            StreamQuality.HIGH -> 256
            StreamQuality.MEDIUM -> 128
            StreamQuality.LOW -> 64
        }
        return AudioStreamInfo(
            codec = codec ?: "AAC",
            bitrate = bitrate,
            sampleRate = 44100,
            bitDepth = 16,
            qualityTier = quality,
            containerFormat = container ?: "M4A",
            isEnhanced = false
        )
    }


    /** Shared artist → genre map for scoring engines. */
    private val ARTIST_GENRE_MAP = mapOf(
        "Arijit Singh" to listOf(" Bollywood", " Romantic"),
        "Shreya Ghoshal" to listOf(" Bollywood", " Classical"),
        "Atif Aslam" to listOf(" Bollywood", " Pop"),
        "Pritam" to listOf(" Bollywood", " Film"),
        "A.R. Rahman" to listOf(" Bollywood", " Classical", " World"),
        "Neha Kakkar" to listOf(" Bollywood", " Pop"),
        "Badshah" to listOf(" Hip-Hop", " Bollywood"),
        "Honey Singh" to listOf(" Hip-Hop", " Bollywood"),
        "Jubin Nautiyal" to listOf(" Bollywood", " Pop"),
        "B Praak" to listOf(" Bollywood", " Punjabi"),
        "Vishal Mishra" to listOf(" Bollywood", " Romantic"),
        "Diljit Dosanjh" to listOf(" Punjabi", " Pop"),
        "AP Dhillon" to listOf(" Punjabi", " Hip-Hop"),
        "Drake" to listOf(" Hip-Hop", " R&B"),
        "Taylor Swift" to listOf(" Pop", " Country"),
        "The Weeknd" to listOf(" R&B", " Pop"),
        "Ed Sheeran" to listOf(" Pop", " Acoustic"),
        "BTS" to listOf(" K-Pop", " Pop"),
        "Eminem" to listOf(" Hip-Hop", " Rap"),
        "Billie Eilish" to listOf(" Pop", " Alternative"),
        "Post Malone" to listOf(" Hip-Hop", " Pop"),
        "Dua Lipa" to listOf(" Pop", " Dance"),
        "Travis Scott" to listOf(" Hip-Hop", " Trap"),
        "Lana Del Rey" to listOf(" Indie", " Pop"),
        "Coldplay" to listOf(" Rock", " Pop"),
        "Imagine Dragons" to listOf(" Rock", " Pop"),
        "Ariana Grande" to listOf(" Pop", " R&B"),
        "Justin Bieber" to listOf(" Pop", " R&B"),
        "Maroon 5" to listOf(" Pop", " Rock"),
        "Sunidhi Chauhan" to listOf(" Bollywood", " Pop"),
        "Sonu Nigam" to listOf(" Bollywood", " Classical")
    )
}
