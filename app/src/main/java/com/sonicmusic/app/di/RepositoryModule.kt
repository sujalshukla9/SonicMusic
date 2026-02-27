package com.sonicmusic.app.di

import com.sonicmusic.app.data.remote.source.AudioStreamExtractor
import com.sonicmusic.app.data.remote.source.NewPipeService
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.data.repository.HistoryRepositoryImpl
import com.sonicmusic.app.data.repository.ListenAgainRepositoryImpl
import com.sonicmusic.app.data.repository.LocalMusicRepositoryImpl
import com.sonicmusic.app.data.repository.PlaylistRepositoryImpl
import com.sonicmusic.app.data.repository.QueueRepositoryImpl
import com.sonicmusic.app.data.repository.RecentSearchRepositoryImpl
import com.sonicmusic.app.data.repository.RecommendationRepositoryImpl
import com.sonicmusic.app.data.repository.SearchRepositoryImpl
import com.sonicmusic.app.data.repository.SongRepositoryImpl
import com.sonicmusic.app.data.repository.ArtistRepositoryImpl
import com.sonicmusic.app.domain.repository.HistoryRepository
import com.sonicmusic.app.domain.repository.ListenAgainRepository
import com.sonicmusic.app.domain.repository.LocalMusicRepository
import com.sonicmusic.app.domain.repository.PlaylistRepository
import com.sonicmusic.app.domain.repository.QueueRepository
import com.sonicmusic.app.domain.repository.RecentSearchRepository
import com.sonicmusic.app.domain.repository.RecommendationRepository
import com.sonicmusic.app.domain.repository.SearchRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.domain.repository.ArtistRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSongRepository(
        impl: SongRepositoryImpl
    ): SongRepository

    @Binds
    @Singleton
    abstract fun bindPlaylistRepository(
        impl: PlaylistRepositoryImpl
    ): PlaylistRepository

    @Binds
    @Singleton
    abstract fun bindHistoryRepository(
        impl: HistoryRepositoryImpl
    ): HistoryRepository

    @Binds
    @Singleton
    abstract fun bindRecentSearchRepository(
        impl: RecentSearchRepositoryImpl
    ): RecentSearchRepository

    @Binds
    @Singleton
    abstract fun bindSearchRepository(
        impl: SearchRepositoryImpl
    ): SearchRepository

    @Binds
    @Singleton
    abstract fun bindLocalMusicRepository(
        impl: LocalMusicRepositoryImpl
    ): LocalMusicRepository

    @Binds
    @Singleton
    abstract fun bindRecommendationRepository(
        impl: RecommendationRepositoryImpl
    ): RecommendationRepository

    @Binds
    @Singleton
    abstract fun bindQueueRepository(
        impl: QueueRepositoryImpl
    ): QueueRepository
    
    @Binds
    @Singleton
    abstract fun bindUserTasteRepository(
        impl: com.sonicmusic.app.data.repository.UserTasteRepositoryImpl
    ): com.sonicmusic.app.domain.repository.UserTasteRepository

    @Binds
    @Singleton
    abstract fun bindArtistRepository(
        impl: ArtistRepositoryImpl
    ): ArtistRepository

    @Binds
    @Singleton
    abstract fun bindListenAgainRepository(
        impl: ListenAgainRepositoryImpl
    ): ListenAgainRepository

    @Binds
    @Singleton
    abstract fun bindQuickPicksRepository(
        impl: com.sonicmusic.app.data.repository.QuickPicksRepositoryImpl
    ): com.sonicmusic.app.domain.repository.QuickPicksRepository

    companion object {
        @Provides
        @Singleton
        fun provideNewPipeService(): NewPipeService {
            return NewPipeService()
        }

        @Provides
        @Singleton
        fun provideAudioStreamExtractor(
            newPipeService: NewPipeService
        ): AudioStreamExtractor {
            return AudioStreamExtractor(newPipeService)
        }
    }
}