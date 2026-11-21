package com.sonicmusic.app.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class LocalSong(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Int, // Seconds, as in Song.
    val filePath: String,
    val albumId: Long? = null,
    val dateAdded: Long,
    val fileSize: Long
) {
    /** Android content URI for the album art, if albumId is available */
    val albumArtUri: String?
        get() = albumId?.let {
            "content://media/external/audio/albumart/$it"
        }
}

private const val DEVICE_SONG_PREFIX = "local_"

internal fun String.deviceLocalSongId(): Long? =
    takeIf { it.startsWith(DEVICE_SONG_PREFIX) }?.removePrefix(DEVICE_SONG_PREFIX)?.toLongOrNull()

fun LocalSong.toSong(): Song = Song(
    id = "$DEVICE_SONG_PREFIX$id",
    title = title,
    artist = artist,
    album = album,
    duration = duration,
    thumbnailUrl = albumArtUri ?: "",
    contentType = ContentType.SONG
)