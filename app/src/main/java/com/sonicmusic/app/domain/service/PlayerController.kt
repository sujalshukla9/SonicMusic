package com.sonicmusic.app.domain.service

import com.sonicmusic.app.domain.model.Song

interface PlayerController {
    suspend fun playNow(song: Song, streamUrl: String)
    suspend fun addToQueue(song: Song, streamUrl: String)
}
