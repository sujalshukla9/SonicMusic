package com.sonicmusic.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for tracking downloaded songs
 */
@Entity(tableName = "downloaded_songs")
data class DownloadedSongEntity(
    @PrimaryKey val songId: String,
    val title: String,
    val artist: String,
    val filePath: String,
    val fileSize: Long,
    val quality: String,
    val downloadedAt: Long,
    val thumbnailUrl: String? = null
)