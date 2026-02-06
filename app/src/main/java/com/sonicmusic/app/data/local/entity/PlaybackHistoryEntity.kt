package com.sonicmusic.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_history")
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val songId: String,
    val playedAt: Long
)
