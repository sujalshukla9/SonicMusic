package com.sonicmusic.app.domain.repository

import com.sonicmusic.app.domain.model.ChartSong
import com.sonicmusic.app.domain.model.ChartArtist
import com.sonicmusic.app.domain.model.ChartAlbum

interface ChartsRepository {
    suspend fun getTopSongs(region: String): List<ChartSong>
    suspend fun getTopArtists(region: String): List<ChartArtist>
    suspend fun getTopAlbums(region: String): List<ChartAlbum>
}
