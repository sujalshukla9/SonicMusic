package com.sonicmusic.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playback_history",
    indices = [
        Index(value = ["playedAt"]),
        Index(value = ["songId", "playedAt"]),
        Index(value = ["artist"])
    ]
)
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val playedAt: Long,
    val playDuration: Int,
    val totalDuration: Int = 0,
    val completed: Boolean
)