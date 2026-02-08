package com.sonicmusic.app.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.sonicmusic.app.data.local.dao.DownloadedSongDao
import com.sonicmusic.app.data.local.dao.LocalSongDao
import com.sonicmusic.app.data.local.dao.PlaybackHistoryDao
import com.sonicmusic.app.data.local.dao.PlaylistDao
import com.sonicmusic.app.data.local.dao.RecentSearchDao
import com.sonicmusic.app.data.local.dao.SongDao
import com.sonicmusic.app.data.local.entity.DownloadedSongEntity
import com.sonicmusic.app.data.local.entity.LocalSongEntity
import com.sonicmusic.app.data.local.entity.PlaybackHistoryEntity
import com.sonicmusic.app.data.local.entity.PlaylistEntity
import com.sonicmusic.app.data.local.entity.PlaylistSongCrossRef
import com.sonicmusic.app.data.local.entity.RecentSearchEntity
import com.sonicmusic.app.data.local.entity.SongEntity

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongCrossRef::class,
        PlaybackHistoryEntity::class,
        LocalSongEntity::class,
        RecentSearchEntity::class,
        DownloadedSongEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class SonicMusicDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun localSongDao(): LocalSongDao
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun downloadedSongDao(): DownloadedSongDao

    companion object {
        const val DATABASE_NAME = "sonic_music.db"
    }
}