package com.sonicmusic.app.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Represents a community-curated playlist from YouTube Music.
 *
 * @param id The unique identifier of the playlist (typically starting with VLPL or PL)
 * @param title The title of the playlist
 * @param creator The channel or user who created/curated the playlist
 * @param thumbnailUrl High-resolution square cover artwork URL
 * @param trackCount Optional numeric track count when provided by Innertube
 * @param trackCountText Optional formatted track count text (e.g. "50 songs", "120 tracks")
 * @param browseId Canonical browse ID for Innertube navigation (e.g. VLPL...)
 * @param viewCountText Optional formatted view or play count (e.g. "1.2M views", "45K plays")
 * @param previewTracks Preview of up to 3 tracks from the playlist
 */
@Immutable
@Serializable
data class CommunityPlaylist(
    val id: String,
    val title: String,
    val creator: String,
    val thumbnailUrl: String,
    val trackCount: Int? = null,
    val trackCountText: String? = null,
    val browseId: String = id,
    val viewCountText: String? = null,
    val previewTracks: List<Song> = emptyList()
)
