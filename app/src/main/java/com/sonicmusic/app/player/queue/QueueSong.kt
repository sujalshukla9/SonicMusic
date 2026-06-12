package com.sonicmusic.app.player.queue

import androidx.compose.runtime.Immutable
import com.sonicmusic.app.domain.model.Song

/**
 * Represent a song in the fast/optimized reactive queue.
 */
@Immutable
data class QueueSong(
    val videoId: String,
    val title: String,
    val artists: String,
    val thumbnailUrl: String,
    val durationMs: Long,
    val isStreamUrlResolved: Boolean = false
) {
    /**
     * Helper to format duration in MM:SS format.
     */
    fun formattedDuration(): String {
        val seconds = durationMs / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%d:%02d", minutes, remainingSeconds)
    }
}

/**
 * Extension mapper from domain Song to QueueSong.
 */
fun Song.toQueueSong(isResolved: Boolean = false): QueueSong {
    return QueueSong(
        videoId = id,
        title = title,
        artists = artist,
        thumbnailUrl = thumbnailUrl,
        durationMs = duration.toLong() * 1000L,
        isStreamUrlResolved = isResolved
    )
}

/**
 * Extension mapper from QueueSong to domain Song.
 */
fun QueueSong.toSong(): Song {
    return Song(
        id = videoId,
        title = title,
        artist = artists,
        duration = (durationMs / 1000).toInt(),
        thumbnailUrl = thumbnailUrl
    )
}
