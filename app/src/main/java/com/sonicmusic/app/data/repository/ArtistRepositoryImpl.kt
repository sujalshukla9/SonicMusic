package com.sonicmusic.app.data.repository

import com.sonicmusic.app.data.local.dao.ArtistPageDao
import com.sonicmusic.app.data.local.dao.FollowedArtistDao
import com.sonicmusic.app.data.local.entity.ArtistPageCacheEntity
import com.sonicmusic.app.data.local.entity.FollowedArtistEntity
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.domain.model.ArtistProfileSection
import com.sonicmusic.app.domain.model.ArtistProfileSectionType
import com.sonicmusic.app.domain.model.ArtistProfile
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.ArtistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtistRepositoryImpl @Inject constructor(
    private val followedArtistDao: FollowedArtistDao,
    private val artistPageDao: ArtistPageDao,
    private val youTubeiService: YouTubeiService
) : ArtistRepository {

    companion object {
        private const val ALBUM_CACHE_TTL_MS = 60 * 60 * 1000L
        private const val MAX_ARTIST_SONG_LIMIT = 600
    }

    private data class CacheEntry<T>(
        val value: T,
        val cachedAtMs: Long
    )

    private val albumSongsCache = ConcurrentHashMap<String, CacheEntry<List<Song>>>()
    private val profileCache = ConcurrentHashMap<String, CacheEntry<ArtistProfile>>()
    private val sectionItemsCache = ConcurrentHashMap<String, CacheEntry<List<Song>>>()
    private val cachePolicy = ArtistCachePolicy()
    private val retryPolicy = RetryPolicy()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun followArtist(name: String, browseId: String?, thumbnailUrl: String?) {
        val canonicalName = canonicalArtistName(name)
        if (canonicalName.isBlank()) return
        val canonicalBrowseId = canonicalBrowseId(browseId)

        canonicalBrowseId?.let { followedArtistDao.unfollowByBrowseId(it) }
        followedArtistDao.unfollow(canonicalName)
        followedArtistDao.follow(
            FollowedArtistEntity(
                artistName = canonicalName,
                browseId = canonicalBrowseId,
                thumbnailUrl = thumbnailUrl,
                followedAt = System.currentTimeMillis()
            )
        )
        invalidateArtistProfileCaches(canonicalName, canonicalBrowseId)
    }

    override suspend fun unfollowArtist(name: String, browseId: String?) {
        val canonicalName = canonicalArtistName(name)
        val canonicalBrowseId = canonicalBrowseId(browseId)
        if (canonicalName.isBlank() && canonicalBrowseId == null) return
        canonicalBrowseId?.let { followedArtistDao.unfollowByBrowseId(it) }
        if (canonicalName.isNotBlank()) {
            followedArtistDao.unfollow(canonicalName)
        }
        invalidateArtistProfileCaches(canonicalName, canonicalBrowseId)
    }

    override fun isFollowed(name: String, browseId: String?): Flow<Boolean> {
        canonicalBrowseId(browseId)?.let { normalizedBrowseId ->
            return followedArtistDao.isFollowedByBrowseId(normalizedBrowseId)
        }

        val canonicalName = canonicalArtistName(name)
        if (canonicalName.isBlank()) return flowOf(false)
        return followedArtistDao.isFollowed(canonicalName)
    }

    override fun getFollowedArtists(): Flow<List<FollowedArtistEntity>> {
        return followedArtistDao.getAllFollowed()
    }

    override fun getFollowedCount(): Flow<Int> {
        return followedArtistDao.getFollowedCount()
    }

    override suspend fun getArtistProfile(
        name: String,
        browseId: String?,
        forceRefresh: Boolean
    ): Result<ArtistProfile> = withContext(Dispatchers.IO) {
        val canonicalName = canonicalArtistName(name)
        val canonicalBrowseId = canonicalBrowseId(browseId)
        val cacheKeys = buildProfileCacheKeys(canonicalName, canonicalBrowseId)
        if (cacheKeys.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Artist name is empty"))
        }
        val now = System.currentTimeMillis()

        val staleMemoryEntry = cacheKeys
            .mapNotNull { key -> profileCache[key] }
            .maxByOrNull { entry -> entry.cachedAtMs }

        if (forceRefresh) {
            cacheKeys.forEach(profileCache::remove)
        }

        if (!forceRefresh) {
            cacheKeys.firstNotNullOfOrNull { key ->
                getCached(profileCache, key, cachePolicy.artistPageTtlMs)
            }?.let { cached ->
                return@withContext Result.success(cached.copy(isStale = false))
            }
        }

        val cachedDbEntries = runCatching {
            cacheKeys.mapNotNull { key ->
                artistPageDao.getArtistPage(key)?.let { entry -> key to entry }
            }
        }.getOrDefault(emptyList())

        if (!forceRefresh) {
            val freshDbEntry = cachedDbEntries.firstOrNull { (_, entry) ->
                !cachePolicy.isExpired(entry.fetchedAt, now)
            }
            if (freshDbEntry != null) {
                val profile = decodeArtistProfile(freshDbEntry.second.data)
                if (profile != null) {
                    profileCache[freshDbEntry.first] = CacheEntry(
                        value = profile,
                        cachedAtMs = freshDbEntry.second.fetchedAt
                    )
                    return@withContext Result.success(profile.copy(isStale = false))
                }
            }
        }

        val remoteResult = retryPolicy.execute {
            youTubeiService.getArtistProfileFromInnertube(
                artistName = canonicalName,
                browseIdHint = canonicalBrowseId
            )
        }

        remoteResult.fold(
            onSuccess = { remote ->
                val sections = remote.sections.map { section ->
                    ArtistProfileSection(
                        type = section.type.toDomainSectionType(),
                        title = section.title,
                        browseId = section.browseId,
                        moreEndpoint = section.moreEndpoint,
                        items = section.items.distinctBy { it.id }
                    )
                }
                val sectionAlbums = sections
                    .asSequence()
                    .filter { section ->
                        section.type == ArtistProfileSectionType.Albums ||
                            section.type == ArtistProfileSectionType.Singles
                    }
                    .flatMap { section -> section.items.asSequence() }
                    .filter { item -> item.id.isNotBlank() }
                    .toList()
                val mergedAlbums = (remote.albums + remote.singles + sectionAlbums)
                    .distinctBy { it.id }

                val songsMoreEndpoint = sections.firstOrNull {
                    it.type == ArtistProfileSectionType.TopSongs
                }?.moreEndpoint ?: remote.songsMoreEndpoint
                val topSongsBrowseId = remote.topSongsBrowseId ?: sections.firstOrNull {
                    it.type == ArtistProfileSectionType.TopSongs
                }?.browseId
                val albumsMoreEndpoint = sections.firstOrNull {
                    it.type == ArtistProfileSectionType.Albums
                }?.moreEndpoint ?: remote.albumsMoreEndpoint
                val singlesMoreEndpoint = sections.firstOrNull {
                    it.type == ArtistProfileSectionType.Singles
                }?.moreEndpoint ?: remote.singlesMoreEndpoint

                val profile = ArtistProfile(
                    name = remote.name.trim().ifBlank { canonicalName },
                    browseId = remote.browseId,
                    imageUrl = remote.imageUrl,
                    bannerUrl = remote.bannerUrl,
                    subscribersText = remote.subscribersText,
                    description = remote.description,
                    shufflePlaylistId = remote.shufflePlaylistId,
                    radioPlaylistId = remote.radioPlaylistId,
                    topSongs = remote.topSongs.distinctBy { it.id }.take(5),
                    albums = mergedAlbums,
                    singles = remote.singles.distinctBy { it.id },
                    videos = remote.videos.distinctBy { it.id },
                    featuredOn = remote.featuredOn.distinctBy { it.id },
                    relatedArtists = remote.relatedArtists.distinctBy { it.id },
                    topSongsBrowseId = topSongsBrowseId,
                    songsMoreEndpoint = songsMoreEndpoint,
                    albumsMoreEndpoint = albumsMoreEndpoint,
                    singlesMoreEndpoint = singlesMoreEndpoint,
                    sections = sections,
                    isStale = false
                )

                val writeKeys = buildProfileCacheKeys(
                    canonicalName = canonicalArtistName(canonicalName.ifBlank { profile.name }),
                    canonicalBrowseId = profile.browseId ?: canonicalBrowseId
                )
                persistProfileCaches(
                    keys = writeKeys.ifEmpty { cacheKeys },
                    profile = profile,
                    fetchedAt = now
                )

                Result.success(profile)
            },
            onFailure = { error ->
                val staleDbEntry = cachedDbEntries
                    .maxByOrNull { (_, entry) -> entry.fetchedAt }
                    ?.second
                val staleDbProfile = staleDbEntry
                    ?.takeIf { entry -> !cachePolicy.isStaleBeyondTolerance(entry.fetchedAt, now) }
                    ?.let { entry -> decodeArtistProfile(entry.data) }
                if (staleDbProfile != null) {
                    return@fold Result.success(staleDbProfile.copy(isStale = true))
                }

                val staleMemoryProfile = staleMemoryEntry
                    ?.takeIf { entry -> !cachePolicy.isStaleBeyondTolerance(entry.cachedAtMs, now) }
                    ?.value
                staleMemoryProfile?.let { stale ->
                    Result.success(stale.copy(isStale = true))
                } ?: Result.failure(error)
            }
        )
    }

    override suspend fun getArtistSongs(
        name: String,
        browseId: String?,
        limit: Int,
        forceRefresh: Boolean
    ): Result<List<Song>> {
        val canonicalName = canonicalArtistName(name)
        val canonicalBrowseId = canonicalBrowseId(browseId)
        if (canonicalName.isBlank() && canonicalBrowseId == null) {
            return Result.failure(IllegalArgumentException("Artist name is empty"))
        }
        val safeLimit = limit.coerceAtLeast(1).coerceAtMost(MAX_ARTIST_SONG_LIMIT)

        return youTubeiService.getArtistSongsFromInnertube(
            artistName = canonicalName,
            browseId = canonicalBrowseId,
            limit = safeLimit
        )
            .map { songs ->
                songs.asSequence()
                    .filter { song -> song.id.isNotBlank() }
                    .distinctBy { song -> song.id }
                    .toList()
            }
    }

    override suspend fun getAlbumSongs(
        albumBrowseId: String,
        forceRefresh: Boolean
    ): Result<List<Song>> {
        val browseId = albumBrowseId.trim()
        if (browseId.isBlank()) {
            return Result.failure(IllegalArgumentException("Album browseId is empty"))
        }

        if (forceRefresh) {
            albumSongsCache.remove(browseId)
        }
        if (!forceRefresh) {
            getCached(albumSongsCache, browseId, ALBUM_CACHE_TTL_MS)?.let { cached ->
                return Result.success(cached)
            }
        }

        return youTubeiService.getAlbumSongsFromInnertube(browseId)
            .map { songs ->
                songs.asSequence()
                    .filter { song -> song.id.isNotBlank() }
                    .distinctBy { song -> song.id }
                    .toList()
            }
            .onSuccess { songs ->
                albumSongsCache[browseId] = CacheEntry(
                    value = songs,
                    cachedAtMs = System.currentTimeMillis()
                )
            }
    }

    override suspend fun getArtistSectionItems(
        artistBrowseId: String,
        sectionType: ArtistProfileSectionType,
        moreEndpoint: String?,
        limit: Int,
        forceRefresh: Boolean
    ): Result<List<Song>> {
        val browseId = canonicalBrowseId(artistBrowseId)
            ?: return Result.failure(IllegalArgumentException("Artist browseId is empty"))
        if (sectionType == ArtistProfileSectionType.Unknown) {
            return Result.failure(IllegalArgumentException("Unknown section type"))
        }
        val safeLimit = limit.coerceAtLeast(1).coerceAtMost(MAX_ARTIST_SONG_LIMIT)
        val endpointToken = moreEndpoint?.trim()?.takeIf { it.isNotEmpty() }
        val cacheKey = buildString {
            append(browseId)
            append("|")
            append(sectionType.name)
            append("|")
            append(endpointToken.orEmpty())
            append("|")
            append(safeLimit)
        }

        if (forceRefresh) {
            sectionItemsCache.remove(cacheKey)
        }
        if (!forceRefresh) {
            getCached(sectionItemsCache, cacheKey, cachePolicy.artistSectionTtlMs)?.let { cached ->
                return Result.success(cached)
            }
        }

        return youTubeiService.getArtistSectionItemsFromInnertube(
            artistBrowseId = browseId,
            sectionType = sectionType.toRemoteSectionType(),
            moreEndpoint = endpointToken,
            limit = safeLimit
        )
            .map { items ->
                items.asSequence()
                    .filter { item -> item.id.isNotBlank() }
                    .distinctBy { item -> item.id }
                    .toList()
            }
            .onSuccess { items ->
                sectionItemsCache[cacheKey] = CacheEntry(
                    value = items,
                    cachedAtMs = System.currentTimeMillis()
                )
            }
    }

    private fun canonicalArtistName(name: String): String {
        return name.trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun canonicalBrowseId(browseId: String?): String? {
        return browseId?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun buildProfileCacheKeys(
        canonicalName: String,
        canonicalBrowseId: String?
    ): List<String> {
        val keys = linkedSetOf<String>()
        canonicalBrowseId?.let { keys += "browse:${it.trim()}" }
        if (canonicalName.isNotBlank()) {
            keys += "name:${canonicalName.lowercase()}"
        }
        return keys.toList()
    }

    private suspend fun persistProfileCaches(
        keys: List<String>,
        profile: ArtistProfile,
        fetchedAt: Long
    ) {
        if (keys.isEmpty()) return
        val payload = json.encodeToString(profile)
        keys.distinct().forEach { key ->
            profileCache[key] = CacheEntry(
                value = profile,
                cachedAtMs = fetchedAt
            )
            artistPageDao.upsertArtistPage(
                ArtistPageCacheEntity(
                    cacheKey = key,
                    data = payload,
                    fetchedAt = fetchedAt
                )
            )
        }
        artistPageDao.purgeOldCache(fetchedAt - cachePolicy.purgeAfterMs)
    }

    private fun decodeArtistProfile(payload: String): ArtistProfile? {
        return runCatching {
            json.decodeFromString<ArtistProfile>(payload)
        }.getOrNull()
    }

    private suspend fun invalidateArtistProfileCaches(name: String, browseId: String?) {
        buildProfileCacheKeys(name, browseId).forEach { key ->
            profileCache.remove(key)
            artistPageDao.deleteArtistPage(key)
        }
    }

    private fun YouTubeiService.ArtistSectionType.toDomainSectionType(): ArtistProfileSectionType {
        return when (this) {
            YouTubeiService.ArtistSectionType.TOP_SONGS -> ArtistProfileSectionType.TopSongs
            YouTubeiService.ArtistSectionType.ALBUMS -> ArtistProfileSectionType.Albums
            YouTubeiService.ArtistSectionType.SINGLES -> ArtistProfileSectionType.Singles
            YouTubeiService.ArtistSectionType.VIDEOS -> ArtistProfileSectionType.Videos
            YouTubeiService.ArtistSectionType.FEATURED_ON -> ArtistProfileSectionType.FeaturedOn
            YouTubeiService.ArtistSectionType.RELATED_ARTISTS -> ArtistProfileSectionType.RelatedArtists
            YouTubeiService.ArtistSectionType.UNKNOWN -> ArtistProfileSectionType.Unknown
        }
    }

    private fun ArtistProfileSectionType.toRemoteSectionType(): YouTubeiService.ArtistSectionType {
        return when (this) {
            ArtistProfileSectionType.TopSongs -> YouTubeiService.ArtistSectionType.TOP_SONGS
            ArtistProfileSectionType.Albums -> YouTubeiService.ArtistSectionType.ALBUMS
            ArtistProfileSectionType.Singles -> YouTubeiService.ArtistSectionType.SINGLES
            ArtistProfileSectionType.Videos -> YouTubeiService.ArtistSectionType.VIDEOS
            ArtistProfileSectionType.FeaturedOn -> YouTubeiService.ArtistSectionType.FEATURED_ON
            ArtistProfileSectionType.RelatedArtists -> YouTubeiService.ArtistSectionType.RELATED_ARTISTS
            ArtistProfileSectionType.Unknown -> YouTubeiService.ArtistSectionType.UNKNOWN
        }
    }

    private fun <T> getCached(
        cache: ConcurrentHashMap<String, CacheEntry<T>>,
        key: String,
        ttlMs: Long
    ): T? {
        val entry = cache[key] ?: return null
        val now = System.currentTimeMillis()
        return if (now - entry.cachedAtMs <= ttlMs) {
            entry.value
        } else {
            cache.remove(key, entry)
            null
        }
    }
}
