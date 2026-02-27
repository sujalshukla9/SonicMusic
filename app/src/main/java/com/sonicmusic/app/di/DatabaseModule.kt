package com.sonicmusic.app.di

import android.content.Context
import androidx.room.Room
import com.sonicmusic.app.data.local.database.SonicMusicDatabase
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
    fun provideDatabase(
        @ApplicationContext context: Context
    ): SonicMusicDatabase {
        val builder = Room.databaseBuilder(
            context,
            SonicMusicDatabase::class.java,
            SonicMusicDatabase.DATABASE_NAME
        ).fallbackToDestructiveMigration()

        if (com.sonicmusic.app.BuildConfig.DEBUG) {
            builder.setQueryCallback({ sqlQuery, bindArgs ->
                android.util.Log.d("RoomQuery", "SQL: $sqlQuery | Args: $bindArgs")
            }, java.util.concurrent.Executors.newSingleThreadExecutor())
        }

        return builder.build()
    }

    @Provides
    fun provideSongDao(database: SonicMusicDatabase) = database.songDao()

    @Provides
    fun providePlaylistDao(database: SonicMusicDatabase) = database.playlistDao()

    @Provides
    fun providePlaybackHistoryDao(database: SonicMusicDatabase) = database.playbackHistoryDao()

    @Provides
    fun provideLocalSongDao(database: SonicMusicDatabase) = database.localSongDao()

    @Provides
    fun provideRecentSearchDao(database: SonicMusicDatabase) = database.recentSearchDao()

    @Provides
    fun provideDownloadedSongDao(database: SonicMusicDatabase) = database.downloadedSongDao()

    @Provides
    fun provideFollowedArtistDao(database: SonicMusicDatabase) = database.followedArtistDao()

    @Provides
    fun provideArtistPageDao(database: SonicMusicDatabase) = database.artistPageDao()
}
