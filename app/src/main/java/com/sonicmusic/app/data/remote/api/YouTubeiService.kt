package com.sonicmusic.app.data.remote.api

import com.sonicmusic.app.domain.model.Song

interface YouTubeiService {
    suspend fun searchSongs(query: String): Result<List<Song>>
    suspend fun getStreamUrl(videoId: String): Result<String>
    suspend fun getSongDetails(videoId: String): Result<Song>
}
