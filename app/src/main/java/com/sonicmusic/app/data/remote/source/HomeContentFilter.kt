package com.sonicmusic.app.data.remote.source

import android.util.Log

/**
 * Raw data structures used during parsing and filtering before final domain mapping.
 */
data class RawHomeSong(
    val videoId: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val thumbnailUrl: String,
    val durationText: String?,
    val videoType: String?
)

data class RawHomeSection(
    val title: String,
    val songs: List<RawHomeSong>
)

/**
 * Filter layer for sanitizing home feed sections to ensure song-only content.
 */
object HomeContentFilter {
    private const val TAG = "HomeContentFilter"

    // Case-insensitive regex blacklist for titles (covers, slowed, mashups, etc.)
    private val titleBlacklistRegex = Regex(
        "(mashup|slowed|reverb|8d\\s+audio|lofi\\s+remix|tiktok|compilation|live|cover)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Filter individual songs:
     * - Must be official audio tracks (MUSIC_VIDEO_TYPE_ATV)
     * - Must not contain blacklisted terms in the title
     */
    fun filterSongs(songs: List<RawHomeSong>): List<RawHomeSong> {
        return songs.filter { song ->
            // 1. Keep only official song uploads (ATV)
            if (song.videoType != "MUSIC_VIDEO_TYPE_ATV") {
                Log.d(TAG, "🚫 Dropped (Not ATV): '${song.title}' (videoType=${song.videoType})")
                return@filter false
            }

            // 2. Remove UGC, bootlegs, and clickbait edits
            if (titleBlacklistRegex.containsMatchIn(song.title)) {
                Log.d(TAG, "🚫 Dropped (Title Blacklist): '${song.title}'")
                return@filter false
            }

            true
        }
    }

    /**
     * Clean and filter sections:
     * - Cleans the songs inside each section.
     * - Discards sections that end up with less than 2 songs to prevent sparse shelves.
     */
    fun filterSections(sections: List<RawHomeSection>): List<RawHomeSection> {
        return sections.map { section ->
            val cleanSongs = filterSongs(section.songs)
            section.copy(songs = cleanSongs)
        }.filter { section ->
            val hasMinSongs = section.songs.size >= 2
            if (!hasMinSongs) {
                Log.d(TAG, "🚫 Section dropped (Less than 2 songs remaining): '${section.title}'")
            }
            hasMinSongs
        }
    }
}
