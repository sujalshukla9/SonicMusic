package com.sonicmusic.app.domain.model

import androidx.compose.runtime.Immutable

/**
 * Suggestion type for rich suggestion search results
 */
enum class SuggestionType {
    SONG, ARTIST, ALBUM, PLAYLIST, VIDEO
}

/**
 * Represent a rich suggestion item (e.g. song, artist, album, playlist, video)
 */
@Immutable
data class SearchSuggestionItem(
    val id: String,            // videoId or browseId
    val type: SuggestionType,
    val title: String,
    val subtitle: String,      // artist name / "Song" / " Album" / subscribers, etc.
    val thumbnailUrl: String
) {
    /**
     * Map a rich search suggestion to a Song entity for direct playback
     */
    fun toSong(): Song {
        // Parse the artist from subtitle (e.g. "Song • KR$NA" or "Video • Artist Name")
        val cleanArtist = if (subtitle.contains(" • ")) {
            val parts = subtitle.split(" • ")
            if (parts.size > 1) {
                val firstPart = parts[0].trim()
                if (firstPart.equals("Song", ignoreCase = true) || firstPart.equals(" Video", ignoreCase = true)) {
                    parts[1].trim()
                } else {
                    firstPart
                }
            } else {
                subtitle.trim()
            }
        } else {
            subtitle.trim()
        }

        return Song(
            id = id,
            title = title,
            artist = cleanArtist,
            duration = 0,
            thumbnailUrl = thumbnailUrl,
            contentType = when (type) {
                SuggestionType.VIDEO -> ContentType.VIDEO
                else -> ContentType.SONG
            }
        )
    }
}
