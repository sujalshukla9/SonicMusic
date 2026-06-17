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
import java.io.File
import java.util.concurrent.Executors
import androidx.media3.datasource.DefaultHttpDataSource

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): SonicMusicDatabase {
        val migration9to10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN contentType TEXT NOT NULL DEFAULT 'SONG'")
            }
        }

        val migration10to11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN isLocal INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE playlists ADD COLUMN remoteId TEXT DEFAULT NULL")
            }
        }

        val builder = Room.databaseBuilder(
            context,
            SonicMusicDatabase::class.java,
            SonicMusicDatabase.DATABASE_NAME
        ).addMigrations(migration9to10, migration10to11)

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

    @Provides
    fun provideBrowseSectionDao(database: SonicMusicDatabase) = database.browseSectionDao()

    @Provides
    @Singleton
    fun provideDatabaseProvider(
        @ApplicationContext context: Context
    ): androidx.media3.database.DatabaseProvider {
        return androidx.media3.database.StandaloneDatabaseProvider(context)
    }

    @Provides
    @Singleton
    fun provideDownloadCache(
        @ApplicationContext context: Context,
        databaseProvider: androidx.media3.database.DatabaseProvider
    ): androidx.media3.datasource.cache.SimpleCache {
        val downloadDir = File(context.getExternalFilesDir(null), "MusicCache").apply { mkdirs() }
        return androidx.media3.datasource.cache.SimpleCache(
            downloadDir,
            androidx.media3.datasource.cache.NoOpCacheEvictor(),
            databaseProvider
        )
    }

    @Provides
    @Singleton
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun provideMedia3DownloadManager(
        @ApplicationContext context: Context,
        databaseProvider: androidx.media3.database.DatabaseProvider,
        downloadCache: androidx.media3.datasource.cache.SimpleCache
    ): androidx.media3.exoplayer.offline.DownloadManager {
        val executor = Executors.newFixedThreadPool(4)
        val baseHttpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = com.sonicmusic.app.player.playback.DynamicUserAgentHttpDataSourceFactory(baseHttpFactory)
            
        return androidx.media3.exoplayer.offline.DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            dataSourceFactory,
            executor
        ).apply {
            maxParallelDownloads = 4
        }
    }
}

