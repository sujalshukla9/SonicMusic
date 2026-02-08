package com.sonicmusic.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_songs")
data class LocalSongEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Int,
    val filePath: String,
    val albumId: Long? = null,
    val dateAdded: Long,
    val fileSize: Long
)