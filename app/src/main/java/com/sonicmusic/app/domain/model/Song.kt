package com.sonicmusic.app.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Content type for filtering music-only content
 */
@Serializable
enum class ContentType {
    @SerialName("song")
    SONG,           // Regular music song
    @SerialName("video")
    VIDEO,          // Music video (visual content)
    @SerialName("podcast")
    PODCAST,        // Podcast episode
    @SerialName("live_stream")
    LIVE_STREAM,    // Live streaming content
    @SerialName("short")
    SHORT,          // Short-form content (YouTube Shorts)
    @SerialName("album")
    ALBUM,          // Full album
    @SerialName("playlist")
    PLAYLIST,       // Playlist (not individual song)
    @SerialName("unknown")
    UNKNOWN,         // Unidentified content type
    @SerialName("artist")
    ARTIST          // Artist profile
}

@Immutable
@Serializable
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val duration: Int,
    val thumbnailUrl: String,
    val year: Int? = null,
    val category: String = "Music",
    val viewCount: Long? = null,
    val isLiked: Boolean = false,
    val likedAt: Long? = null,
    val contentType: ContentType = ContentType.SONG  // Default to SONG for backward compatibility
) {
    fun formattedDuration(): String {
        val minutes = duration / 60
        val seconds = duration % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    /**
     * Check if this is a music song (not video, podcast, live stream, etc.)
     */
    fun isMusicSong(): Boolean = contentType == ContentType.SONG

    /**
     * Check if content should be shown in music queue
     * More lenient - allows UNKNOWN songs that have reasonable duration
     */
    fun isValidMusicContent(): Boolean {
        return when (contentType) {
            ContentType.SONG -> true
            ContentType.VIDEO -> false  // Exclude music videos
            ContentType.PODCAST -> false // Exclude podcasts
            ContentType.LIVE_STREAM -> false // Exclude live streams
            ContentType.SHORT -> false  // Exclude shorts
            ContentType.ALBUM -> false  // Exclude full albums
            ContentType.PLAYLIST -> false // Exclude playlists
            ContentType.ARTIST -> false // Exclude artists
            ContentType.UNKNOWN -> {
                // For unknown content, accept if it has a reasonable duration for a song
                // (between 30 seconds and 15 minutes)
                duration in 30..900
            }
        }
    }
}