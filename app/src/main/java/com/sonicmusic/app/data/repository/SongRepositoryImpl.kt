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
import com.sonicmusic.app.domain.model.AudioStreamCandidate
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.sonicmusic.app.data.local.dao.DownloadedSongDao
import com.sonicmusic.app.data.mapper.toEntity
import com.sonicmusic.app.data.mapper.toSong
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SongRepositoryImpl @Inject constructor(
    private val songDao: SongDao,
    private val downloadedSongDao: DownloadedSongDao,
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

    data class CachedStream(
        val url: String,
        val info: AudioStreamInfo,
        val quality: StreamQuality,
        val expiresAtMs: Long
    )

    // Ephemeral in-memory stream cache (never persist temporary cipher URLs to disk DB)
    private val memoryStreamCache = ConcurrentHashMap<String, CachedStream>()
    private val streamQualityCache = ConcurrentHashMap<String, StreamQuality>()
    private val streamInfoCache = ConcurrentHashMap<String, AudioStreamInfo>()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                songDao.clearAllCachedStreamUrls()
                Log.d(TAG, "Cleared all legacy cached stream URLs from database")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear legacy cached stream URLs", e)
            }
        }
    }
    
    override suspend fun searchSongs(query: String, limit: Int): Result<List<Song>> {
        return youTubeiService.searchSongs(query, limit)
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
                        cachedStreamUrl = null,
                        cacheExpiry = null
                    )
                } else {
                    song.toEntity().copy(
                        cachedStreamUrl = null,
                        cacheExpiry = null
                    )
                }
                songDao.insertSong(entityToInsert)
            }
    }

    override suspend fun getStreamUrl(songId: String, quality: StreamQuality, forceRefresh: Boolean): Result<String> {
        return getStreamWithInfo(songId, quality, forceRefresh).map { it.first }
    }

    override suspend fun getStreamWithInfo(
        songId: String,
        quality: StreamQuality,
        forceRefresh: Boolean,
    ): Result<Pair<String, AudioStreamInfo>> {
        val effectiveQuality = resolveQuality(quality)

        // Prefer offline file when available.
        songDownloadManager.getPlaybackFile(songId).getOrNull()?.let { localFile ->
            val localUri = localFile.absolutePath
            val localInfo = streamInfoCache[songId] ?: buildLocalStreamInfo(songId, localFile)
            streamInfoCache[songId] = localInfo
            // Note: do not mutate global audioEngine here, as getStreamWithInfo can be called for metadata preview of any song.
            return Result.success(localUri to localInfo)
        }

        if (!forceRefresh) {
            val cached = memoryStreamCache[songId]
            if (cached != null &&
                cached.expiresAtMs > (System.currentTimeMillis() + 60_000L) &&
                cached.quality == effectiveQuality
            ) {
                streamInfoCache[songId] = cached.info
                return Result.success(cached.url to cached.info)
            }
        }

        val (streamUrl, info) = audioStreamExtractor.extractAudioStream(songId, effectiveQuality, forceRefresh)
            .getOrElse { return Result.failure(it) }

        val expiry = AudioStreamCandidate.extractExpiryFromUrl(streamUrl)
            ?: (System.currentTimeMillis() + (15 * 60 * 1000L))
        memoryStreamCache[songId] = CachedStream(streamUrl, info, effectiveQuality, expiry)
        streamQualityCache[songId] = effectiveQuality
        streamInfoCache[songId] = info

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
                val fallbackSongs = youTubeiService.searchSongs("top international pop hits", safeLimit)
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
        memoryStreamCache.remove(songId)
        streamQualityCache.remove(songId)
        streamInfoCache.remove(songId)
        val cachedSong = songDao.getSongById(songId)
        cachedSong?.let {
            songDao.updateSong(it.copy(cachedStreamUrl = null, cacheExpiry = null))
        }
        Log.d(TAG, "Cleared cached stream URL and stream info for: $songId")
    }

    override suspend fun cacheSong(song: Song) {
        val existing = songDao.getSongById(song.id)
        val entity = song.toEntity().let { base ->
            if (existing != null) {
                base.copy(
                    isLiked = existing.isLiked,
                    likedAt = existing.likedAt,
                    cachedStreamUrl = null,
                    cacheExpiry = null
                )
            } else {
                base.copy(
                    cachedStreamUrl = null,
                    cacheExpiry = null
                )
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
        return latinRatio >= 0.70f
    }

    private suspend fun buildLocalStreamInfo(songId: String, localFile: File): AudioStreamInfo {
        val ext = localFile.extension.lowercase(Locale.ROOT)
        val isOpus = ext == "opus" || ext == "webm"
        val isAac = ext == "m4a" || ext == "mp4" || ext == "aac"
        val isFlac = ext == "flac"

        val codec = when {
            isOpus -> "OPUS"
            isAac -> "AAC"
            isFlac -> "FLAC"
            else -> ext.uppercase(Locale.ROOT)
        }
        val container = when {
            isOpus -> "WebM"
            isAac -> "M4A"
            isFlac -> "FLAC"
            else -> ext.uppercase(Locale.ROOT)
        }
        var localSampleRate = 0
        var localBitDepth = 0
        var localChannels = 0
        try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(localFile.absolutePath)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()?.let {
                    if (it > 0) localSampleRate = it
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()?.let {
                    if (it > 0) localBitDepth = it
                }
            }
            retriever.release()
        } catch (_: Exception) {}

        val downloadedSong = downloadedSongDao.getDownloadedSong(songId)
        val durationMs = downloadedSong?.durationMs ?: 0L
        val fileSize = if (localFile.exists() && localFile.length() > 0) localFile.length() else (downloadedSong?.fileSizeBytes ?: 0L)
        val calculatedBitrate = if (durationMs > 0 && fileSize > 0) {
            ((fileSize * 8) / durationMs).toInt()
        } else {
            0
        }

        return AudioStreamInfo(
            codec = codec,
            bitrate = calculatedBitrate,
            sampleRate = localSampleRate,
            bitDepth = localBitDepth,
            qualityTier = AudioStreamInfo.qualityTierFromStream(codec, calculatedBitrate),
            containerFormat = container,
            channelCount = localChannels,
            isEnhanced = false,
            isLocal = true,
            contentLength = fileSize.takeIf { it > 0 },
        )
    }
}
