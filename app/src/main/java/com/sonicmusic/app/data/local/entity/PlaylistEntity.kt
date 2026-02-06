package com.sonicmusic.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String?,
    val coverArtUrl: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "playlist_songs",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("playlistId"), Index("songId")]
)
data class PlaylistSongCrossRef(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val songId: String,
    val position: Int,
    val addedAt: Long
)
