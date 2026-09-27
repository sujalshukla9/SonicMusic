package com.sonicmusic.app.features.player.data

import com.sonicmusic.app.domain.repository.PlayerRepository
import com.sonicmusic.app.domain.repository.SongCompletedEvent
import com.sonicmusic.app.features.player.service.PlayerServiceConnection
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerRepositoryImpl @Inject constructor(
    private val playerServiceConnection: PlayerServiceConnection
) : PlayerRepository {
    override val songCompletedEvents: SharedFlow<SongCompletedEvent> = playerServiceConnection.songCompletedEvents
}
