package com.sonicmusic.app.features.home.data

import android.util.Log
import com.sonicmusic.app.data.remote.source.YtmScopeGuard

import com.sonicmusic.app.domain.model.ArtistRef

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
    val videoType: String?,
    val artistBrowseIds: List<String> = emptyList(),
    val artistRefs: List<ArtistRef> = emptyList()
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
     * - Must be official audio tracks (MUSIC_VIDEO_TYPE_ATV) or official music videos (MUSIC_VIDEO_TYPE_OMV)
     * - Must not contain blacklisted terms in the title
     */
    fun filterSongs(songs: List<RawHomeSong>): List<RawHomeSong> {
        val filtered = songs.filter { song ->
            // 1. Remove UGC, bootlegs, and clickbait edits using centralized blacklist
            if (YtmScopeGuard.isTitleBlacklisted(song.title)) {
                Log.d(TAG, "Dropped (Title Blacklist): '${song.title}'")
                return@filter false
            }

            // 2. Keep only official song uploads (ATV) and official music videos (OMV)
            val isValidType = song.videoType == "MUSIC_VIDEO_TYPE_ATV" || song.videoType == "MUSIC_VIDEO_TYPE_OMV"
            if (!isValidType) {
                // Fallback: if artist looks valid / Topic channel, allow with warning
                val isTopicOrVevo = song.artists.any { it.endsWith(" - Topic", ignoreCase = true) || it.endsWith("VEVO", ignoreCase = true) }
                if (song.videoType.isNullOrBlank() && isTopicOrVevo) {
                    return@filter true
                }
                Log.d(TAG, "Dropped (Non-Official Type): '${song.title}' (videoType=${song.videoType})")
                return@filter false
            }

            true
        }

        // Deduplicate songs by videoId
        return YtmScopeGuard.deduplicateByVideoId(filtered) { it.videoId }
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
                Log.d(TAG, "Section dropped (Less than 2 songs remaining): '${section.title}'")
            }
            hasMinSongs
        }
    }
}
