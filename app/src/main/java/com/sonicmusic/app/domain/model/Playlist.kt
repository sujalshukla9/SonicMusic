package com.sonicmusic.app.domain.model

import androidx.compose.runtime.Immutable

internal const val YOUTUBE_ACCOUNT_PLAYLIST_PREFIX = "youtube-account:"

@Immutable
data class Playlist(
    val id: Long = 0,
    val name: String,
    val description: String? = null,
    val coverArtUrl: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val songCount: Int = 0,
    val songs: List<Song> = emptyList(),
    val isLocal: Boolean = true,
    val remoteId: String? = null
) {
    val isYouTubeAccountPlaylist: Boolean
        get() = remoteId?.startsWith(YOUTUBE_ACCOUNT_PLAYLIST_PREFIX) == true
}
