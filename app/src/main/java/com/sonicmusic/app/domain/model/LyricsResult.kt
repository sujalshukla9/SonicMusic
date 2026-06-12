package com.sonicmusic.app.domain.model

/**
 * Represents a single line of synchronized lyrics.
 * @param timeMs Timestamp in milliseconds
 * @param text Display text (may be transliterated)
 * @param originalText Original script text (e.g., Devanagari), null if same as text
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val originalText: String? = null
)

/**
 * Result of a lyrics fetch operation.
 */
sealed class LyricsResult {
    /**
     * Plain text lyrics were found successfully.
     * @param text The full lyrics text (may be transliterated)
     * @param originalText Original script text, null if same as text
     * @param source Attribution source (e.g. "LyricFind", "Musixmatch")
     */
    data class Found(
        val text: String,
        val originalText: String? = null,
        val source: String? = null
    ) : LyricsResult()

    /**
     * Synchronized (LRC) lyrics were found successfully.
     * @param lines The parsed lyric lines with timestamps
     * @param source Attribution source 
     */
    data class FoundSynced(
        val lines: List<LyricLine>,
        val source: String? = null
    ) : LyricsResult()

    /** No lyrics are available for this song. */
    data object NotFound : LyricsResult()

    /** An error occurred while fetching lyrics. */
    data class Error(val message: String) : LyricsResult()
}
