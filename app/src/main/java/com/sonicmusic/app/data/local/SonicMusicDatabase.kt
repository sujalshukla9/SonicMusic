package com.sonicmusic.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.sonicmusic.app.data.local.dao.PlaylistDao
import com.sonicmusic.app.data.local.dao.SongDao
import com.sonicmusic.app.data.local.entity.PlaylistEntity
import com.sonicmusic.app.data.local.entity.PlaylistSongCrossRef
import com.sonicmusic.app.data.local.entity.SongEntity

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongCrossRef::class,
        com.sonicmusic.app.data.local.entity.SearchHistoryEntity::class,
        com.sonicmusic.app.data.local.entity.PlaybackHistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SonicMusicDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun searchHistoryDao(): com.sonicmusic.app.data.local.dao.SearchHistoryDao
    abstract fun playbackHistoryDao(): com.sonicmusic.app.data.local.dao.PlaybackHistoryDao


    companion object {
        const val DATABASE_NAME = "sonic_music_db"
    }
}
