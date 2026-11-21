package com.sonicmusic.app.features.player.presentation

data class PlayerUiState(
    val title: String,
    val artist: String,
    val albumArtUrl: String,
    val isPlaying: Boolean,
    val currentPositionMs: Long,
    val durationMs: Long,
    val canSkipNext: Boolean,
    val canSkipPrevious: Boolean,
    val isBuffering: Boolean
)

private val ALBUM_TYPE_REGEX = Regex("(?i)\\s*[-–—:]?\\s*(single|ep|album|remix|version)\\b")
private val BRACKETED_TAG_REGEX = Regex("(?i)\\s*[\\(\\[](single|ep|official|audio|video|lyrics|deluxe|remix|version)[^\\]\\)]*[\\)\\]]")
private val NON_ALPHANUM_REGEX = Regex("[^a-z0-9]")

/**
 * Checks whether the given album name matches or is redundant with the song title.
 * Used to avoid displaying the song name at the end of the artist name line in player controls.
 */
fun isAlbumRedundantWithSongTitle(albumName: String?, songTitle: String?): Boolean {
    if (albumName.isNullOrBlank() || songTitle.isNullOrBlank()) return true

    val trimmedAlbum = albumName.trim()
    val trimmedSong = songTitle.trim()

    if (trimmedAlbum.equals(trimmedSong, ignoreCase = true)) return true

    fun normalize(text: String): String {
        return text.lowercase(java.util.Locale.ROOT)
            .replace(ALBUM_TYPE_REGEX, "")
            .replace(BRACKETED_TAG_REGEX, "")
            .replace(NON_ALPHANUM_REGEX, "")
            .trim()
    }

    val normAlbum = normalize(trimmedAlbum)
    val normSong = normalize(trimmedSong)

    if (normAlbum.isEmpty() || normSong.isEmpty()) return true
    if (normAlbum == normSong) return true
    if (normAlbum.contains(normSong) || normSong.contains(normAlbum)) return true

    return false
}
