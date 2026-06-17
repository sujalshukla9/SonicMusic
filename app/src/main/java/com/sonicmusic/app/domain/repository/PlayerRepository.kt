package com.sonicmusic.app.domain.repository

import kotlinx.coroutines.flow.SharedFlow

data class SongCompletedEvent(
    val videoId: String,
    val timestamp: Long
)

interface PlayerRepository {
    val songCompletedEvents: SharedFlow<SongCompletedEvent>
}
