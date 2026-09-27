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
data class ArtistRef(
    val name: String,
    val browseId: String? = null
)

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
    val contentType: ContentType = ContentType.SONG,  // Default to SONG for backward compatibility
    val albumBrowseId: String? = null,
    val albumName: String? = null,
    val artists: List<ArtistRef> = emptyList()
) {
    /**
     * Resolves individual artist references.
     * Prefers explicit [artists] list; falls back to parsing [artist] display text and [artistId].
     */
    val resolvedArtists: List<ArtistRef>
        get() {
            if (artists.isNotEmpty()) return artists
            val trimmedName = artist.trim()
            if (trimmedName.isBlank()) return emptyList()
            val ids = artistId?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() && it != "null" } ?: emptyList()

            val lower = trimmedName.lowercase(java.util.Locale.ROOT)
            if (isKnownCompositeBand(lower)) {
                return listOf(ArtistRef(trimmedName, ids.firstOrNull()))
            }

            val names = trimmedName.split(
                Regex("\\s*[,;/+×|\\u00d7]\\s*|\\s*&\\s*|\\s+(?:and|with|w/|feat\\.?|ft\\.?|x|vs\\.?)\\s+", RegexOption.IGNORE_CASE)
            ).map { it.trim() }.filter { it.isNotBlank() }

            return when {
                names.size == ids.size && ids.isNotEmpty() -> {
                    names.zip(ids) { n, id -> ArtistRef(n, id) }
                }
                names.size <= 1 -> {
                    listOf(ArtistRef(names.firstOrNull() ?: trimmedName, ids.firstOrNull()))
                }
                ids.isNotEmpty() -> {
                    // Preserve available IDs by index — primary artist keeps its browseId
                    names.mapIndexed { index, name ->
                        ArtistRef(name, ids.getOrNull(index))
                    }
                }
                else -> {
                    names.map { n -> ArtistRef(n, null) }
                }
            }
        }

    companion object {
        private val KNOWN_COMPOSITE_BANDS = setOf(
            "earth, wind & fire",
            "simon & garfunkel",
            "crosby, stills, nash & young",
            "crosby, stills & nash",
            "emerson, lake & palmer",
            "blood, sweat & tears",
            "brooks & dunn",
            "dan + shay",
            "florida georgia line",
            "hall & oates",
            "daryl hall & john oates",
            "kool & the gang",
            "kc & the sunshine band",
            "joan jett & the blackhearts",
            "tom petty and the heartbreakers",
            "bob marley & the wailers",
            "huey lewis & the news",
            "gladys knight & the pips",
            "martha & the vandellas",
            "sly & the family stone",
            "florence + the machine",
            "marina and the diamonds",
            "siouxsie and the banshees",
            "echo & the bunnymen",
            "me first and the gimme gimmes",
            "captain & tennille",
            "peaches & herb",
            "bell biv devoe",
            "tony! toni! toné!",
            "ziggy marley & the melody makers"
        )

        fun isKnownCompositeBand(nameLower: String): Boolean {
            return nameLower in KNOWN_COMPOSITE_BANDS
        }
    }

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