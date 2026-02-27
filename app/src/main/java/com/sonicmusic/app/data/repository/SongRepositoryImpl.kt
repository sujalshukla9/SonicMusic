package com.sonicmusic.app.data.repository

import android.util.Log
import com.sonicmusic.app.data.downloadmanager.SongDownloadManager
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
import com.sonicmusic.app.domain.usecase.NewReleaseScoringEngine
import com.sonicmusic.app.domain.usecase.TrendingScoringEngine
import com.sonicmusic.app.player.audio.AudioEngine
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
) : SongRepository {
    
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
        if (cachedSong != null) {
            return Result.success(cachedSong.toSong())
        }

        // Fetch from API
        return youTubeiService.getSongDetails(id)
            .onSuccess { song ->
                songDao.insertSong(song.toEntity())
            }
    }

    override suspend fun getStreamUrl(songId: String, quality: StreamQuality): Result<String> {
        val effectiveQuality = resolveQuality(quality)

        // Prefer offline file when the song is downloaded.
        songDownloadManager.getPlaybackFile(songId).getOrNull()?.let { localFile ->
            val localUri = localFile.toURI().toString()
            Log.d(TAG, "📦 Using offline file for song=$songId")
            return Result.success(localUri)
        }

        // Check if we have a cached stream URL
        val cachedSong = songDao.getSongById(songId)
        if (cachedSong?.cachedStreamUrl != null &&
            cachedSong.cacheExpiry != null && 
            cachedSong.cacheExpiry > System.currentTimeMillis() &&
            streamQualityCache[songId] == effectiveQuality
        ) {
            return Result.success(cachedSong.cachedStreamUrl)
        }

        val streamUrl = audioStreamExtractor.extractAudioStreamUrl(songId, effectiveQuality)
            .getOrElse { return Result.failure(it) }

        streamQualityCache[songId] = effectiveQuality

        Log.d(
            TAG,
            "🎚️ Stream quality requested=${quality.name}, effective=${effectiveQuality.name}, source=innertube_opus, song=$songId",
        )

        // Cache the URL for 30 minutes (YouTube URLs expire quickly)
        val expiry = System.currentTimeMillis() + (30 * 60 * 1000)
        songDao.updateSong(
            cachedSong?.copy(
                cachedStreamUrl = streamUrl,
                cacheExpiry = expiry,
            ) ?: SongEntity(
                id = songId,
                title = "",
                artist = "",
                duration = 0,
                thumbnailUrl = "",
                cachedStreamUrl = streamUrl,
                cacheExpiry = expiry,
            ),
        )

        return Result.success(streamUrl)
    }

    override suspend fun getStreamWithInfo(
        songId: String,
        quality: StreamQuality,
    ): Result<Pair<String, AudioStreamInfo>> {
        val effectiveQuality = resolveQuality(quality)

        // Prefer offline file when available.
        songDownloadManager.getPlaybackFile(songId).getOrNull()?.let { localFile ->
            val localUri = localFile.toURI().toString()
            val localInfo = fallbackStreamInfo(
                quality = StreamQuality.BEST,
                codec = "LOCAL",
                container = localFile.extension.ifBlank { "File" },
            )
            audioEngine.updateStreamInfo(localInfo)
            return Result.success(localUri to localInfo)
        }

        val cachedSong = songDao.getSongById(songId)
        if (cachedSong?.cachedStreamUrl != null &&
            cachedSong.cacheExpiry != null &&
            cachedSong.cacheExpiry > System.currentTimeMillis() &&
            streamQualityCache[songId] == effectiveQuality
        ) {
            val cachedInfo = fallbackStreamInfo(quality = effectiveQuality)
            audioEngine.updateStreamInfo(cachedInfo)
            return Result.success(cachedSong.cachedStreamUrl to cachedInfo)
        }

        val (streamUrl, info) = audioStreamExtractor.extractAudioStream(songId, effectiveQuality)
            .getOrElse { return Result.failure(it) }

        streamQualityCache[songId] = effectiveQuality

        val expiry = System.currentTimeMillis() + (30 * 60 * 1000)
        songDao.updateSong(
            cachedSong?.copy(
                cachedStreamUrl = streamUrl,
                cacheExpiry = expiry,
            ) ?: SongEntity(
                id = songId,
                title = "",
                artist = "",
                duration = 0,
                thumbnailUrl = "",
                cachedStreamUrl = streamUrl,
                cacheExpiry = expiry,
            ),
        )

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
            val rawReleases = youTubeiService.getNewReleases(limit * 2)
                .getOrNull().orEmpty()
            if (rawReleases.isEmpty()) return Result.success(emptyList())

            val tasteProfile = userTasteRepository.getUserTasteProfile()
            val playedIds = runCatching { playbackHistoryDao.getAllPlayedSongIds() }
                .getOrDefault(emptyList()).toSet()
            val followedNames = runCatching {
                followedArtistDao.getAllFollowed().first().map { it.artistName }
            }.getOrDefault(emptyList()).toSet()

            val ranked = NewReleaseScoringEngine.scoreAndRank(
                releases = rawReleases,
                userTopArtists = tasteProfile.topArtists,
                followedArtistIds = followedNames,
                userTopGenres = tasteProfile.topGenres,
                userLanguages = tasteProfile.preferredLanguages,
                playedSongIds = playedIds,
                artistGenreMap = ARTIST_GENRE_MAP
            )

            Log.d(TAG, "🆕 New Releases: ${rawReleases.size} raw → ${ranked.size} ranked")
            Result.success(ranked.take(limit))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "❌ New releases scoring failed, falling back", e)
            youTubeiService.getNewReleases(limit)
        }
    }

    override suspend fun getTrending(limit: Int): Result<List<Song>> {
        return try {
            val rawTrending = youTubeiService.getTrendingSongs(limit * 2)
                .getOrNull().orEmpty()
            if (rawTrending.isEmpty()) return Result.success(emptyList())

            val tasteProfile = userTasteRepository.getUserTasteProfile()
            val playedIds = runCatching { playbackHistoryDao.getAllPlayedSongIds() }
                .getOrDefault(emptyList()).toSet()

            val ranked = TrendingScoringEngine.personalizeAndRank(
                trending = rawTrending,
                userTopGenres = tasteProfile.topGenres,
                userTopArtists = tasteProfile.topArtists,
                userLanguages = tasteProfile.preferredLanguages,
                playedSongIds = playedIds,
                artistGenreMap = ARTIST_GENRE_MAP
            )

            Log.d(TAG, "🔥 Trending: ${rawTrending.size} raw → ${ranked.size} ranked")
            Result.success(ranked.take(limit))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "❌ Trending scoring failed, falling back", e)
            youTubeiService.getTrendingSongs(limit)
        }
    }

    override suspend fun getEnglishHits(limit: Int): Result<List<Song>> {
        val safeLimit = limit.coerceAtLeast(1).coerceAtMost(200)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val queries = listOf(
            "top english songs $year",
            "english pop hits $year",
            "global english hits",
            "billboard hot 100 songs $year"
        )

        val merged = linkedMapOf<String, Song>()
        for (query in queries) {
            youTubeiService.searchSongs(query, safeLimit * 2).getOrNull().orEmpty()
                .filter(::isLikelyEnglishTrack)
                .forEach { song -> merged.putIfAbsent(song.id, song) }
            if (merged.size >= safeLimit) break
        }

        if (merged.size < safeLimit) {
            youTubeiService.getTrendingSongs(safeLimit * 2).getOrNull().orEmpty()
                .filter(::isLikelyEnglishTrack)
                .forEach { song -> merged.putIfAbsent(song.id, song) }
        }

        val hits = merged.values.take(safeLimit)
        return if (hits.isNotEmpty()) {
            Result.success(hits)
        } else {
            Result.success(
                youTubeiService.getEnglishHits(safeLimit * 2)
                    .getOrNull()
                    .orEmpty()
                    .filter(::isLikelyEnglishTrack)
                    .take(safeLimit)
            )
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
        // BEST means adaptive quality based on network/device settings.
        // Explicit tiers (LOW/MEDIUM/HIGH) should be respected as-is.
        return when (requested) {
            StreamQuality.BEST -> audioEngine.getOptimalQuality()
            else -> requested
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
            StreamQuality.BEST -> 192
            StreamQuality.HIGH -> 160
            StreamQuality.MEDIUM -> 128
            StreamQuality.LOW -> 64
        }
        return AudioStreamInfo(
            codec = codec ?: "OPUS",
            bitrate = bitrate,
            sampleRate = 44100,
            bitDepth = 16,
            qualityTier = quality,
            containerFormat = container ?: "WebM",
            isEnhanced = false
        )
    }

    /** Shared artist → genre map for scoring engines. */
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
