package com.sonicmusic.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 9-state enum supporting both requested new states and legacy UI states.
 */
enum class DownloadStatus {
    PENDING,
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    REMOVED,
    RETRYING,
    UNKNOWN
}

/**
 * Entity for tracking downloads in Room database.
 * Matches requested fields and includes backward compatibility computed properties.
 */
@Entity(tableName = "downloaded_songs_v2")
data class DownloadedSong(
    @PrimaryKey val videoId: String,
    val title: String,
    val artists: String, // Comma separated list of artists
    val thumbnailUrl: String,
    val localFilePath: String,
    val localThumbnailPath: String,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val downloadedAt: Long,
    val status: DownloadStatus
) {
    // Backward compatibility helper properties
    val songId: String get() = videoId
    val artist: String get() = artists
    val filePath: String get() = localFilePath
    val fileSize: Long get() = fileSizeBytes

    fun toSong(): com.sonicmusic.app.domain.model.Song = com.sonicmusic.app.domain.model.Song(
        id = videoId,
        title = title,
        artist = artists,
        duration = (durationMs / 1000).toInt(),
        thumbnailUrl = thumbnailUrl
    )
}
