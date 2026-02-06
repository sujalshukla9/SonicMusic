package com.sonicmusic.app.di

import android.content.Context
import androidx.room.Room
import com.sonicmusic.app.data.local.SonicMusicDatabase
import com.sonicmusic.app.data.local.dao.PlaylistDao
import com.sonicmusic.app.data.local.dao.SongDao
import com.sonicmusic.app.data.local.dao.SearchHistoryDao
import com.sonicmusic.app.data.local.dao.PlaybackHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SonicMusicDatabase {
        return Room.databaseBuilder(
            context,
            SonicMusicDatabase::class.java,
            SonicMusicDatabase.DATABASE_NAME
        ).build()
    }

    @Provides
    @Singleton
    fun provideSongDao(database: SonicMusicDatabase): SongDao {
        return database.songDao()
    }

    @Provides
    @Singleton
    fun providePlaylistDao(database: SonicMusicDatabase): PlaylistDao {
        return database.playlistDao()
    }

    @Provides
    @Singleton
    fun provideSearchHistoryDao(database: SonicMusicDatabase): SearchHistoryDao {
        return database.searchHistoryDao()
    }

    @Provides
    @Singleton
    fun providePlaybackHistoryDao(database: SonicMusicDatabase): PlaybackHistoryDao {
        return database.playbackHistoryDao()
    }
}
