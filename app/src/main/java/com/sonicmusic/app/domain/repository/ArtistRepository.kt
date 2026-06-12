package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.data.local.entity.FollowedArtistEntity
import com.sonicmusic.app.domain.model.ArtistProfile
import com.sonicmusic.app.domain.model.ArtistProfileSectionType
import com.sonicmusic.app.domain.model.Song
import kotlinx.coroutines.flow.Flow

interface ArtistRepository {
    suspend fun followArtist(name: String, browseId: String? = null, thumbnailUrl: String?)
    suspend fun unfollowArtist(name: String, browseId: String? = null)
    fun isFollowed(name: String, browseId: String? = null): Flow<Boolean>
    fun getFollowedArtists(): Flow<List<FollowedArtistEntity>>
    fun getFollowedCount(): Flow<Int>

    suspend fun getArtistProfile(
        name: String,
        browseId: String? = null,
        forceRefresh: Boolean = false
    ): Result<ArtistProfile>

    suspend fun getArtistSongs(
        name: String,
        browseId: String? = null,
        limit: Int = 120,
        forceRefresh: Boolean = false
    ): Result<List<Song>>

    suspend fun getAlbumSongs(
        albumBrowseId: String,
        forceRefresh: Boolean = false
    ): Result<List<Song>>

    suspend fun getArtistSectionItems(
        artistBrowseId: String,
        sectionType: ArtistProfileSectionType,
        moreEndpoint: String? = null,
        limit: Int = 120,
        forceRefresh: Boolean = false
    ): Result<List<Song>>
}
