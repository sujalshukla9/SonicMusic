package com.sonicmusic.app.di

import com.sonicmusic.app.data.remote.source.AudioStreamExtractor
import com.sonicmusic.app.data.remote.source.NewPipeService
import com.sonicmusic.app.data.remote.source.YouTubeiService
import com.sonicmusic.app.features.library.data.HistoryRepositoryImpl
import com.sonicmusic.app.features.recommendations.data.ListenAgainRepositoryImpl
import com.sonicmusic.app.features.library.data.LocalMusicRepositoryImpl
import com.sonicmusic.app.features.library.data.PlaylistRepositoryImpl
import com.sonicmusic.app.features.queue.data.QueueRepositoryImpl
import com.sonicmusic.app.features.search.data.RecentSearchRepositoryImpl
import com.sonicmusic.app.features.recommendations.data.RecommendationRepositoryImpl
import com.sonicmusic.app.features.search.data.SearchRepositoryImpl
import com.sonicmusic.app.features.search.data.SearchSuggestionRepositoryImpl
import com.sonicmusic.app.data.repository.SongRepositoryImpl
import com.sonicmusic.app.features.artists.data.ArtistRepositoryImpl
import com.sonicmusic.app.features.albums.data.AlbumRepositoryImpl
import com.sonicmusic.app.domain.repository.AlbumRepository
import com.sonicmusic.app.features.library.data.LyricsRepositoryImpl
import com.sonicmusic.app.domain.repository.HistoryRepository
import com.sonicmusic.app.domain.repository.ListenAgainRepository
import com.sonicmusic.app.domain.repository.LocalMusicRepository
import com.sonicmusic.app.domain.repository.PlaylistRepository
import com.sonicmusic.app.domain.repository.QueueRepository
import com.sonicmusic.app.domain.repository.RecentSearchRepository
import com.sonicmusic.app.domain.repository.RecommendationRepository
import com.sonicmusic.app.domain.repository.SearchRepository
import com.sonicmusic.app.domain.repository.SearchSuggestionRepository
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.domain.repository.ArtistRepository
import com.sonicmusic.app.domain.repository.LyricsRepository
import com.sonicmusic.app.domain.repository.PlayerRepository
import com.sonicmusic.app.domain.repository.HomeRepository
import com.sonicmusic.app.features.player.data.PlayerRepositoryImpl
import com.sonicmusic.app.features.home.data.InnertubeHomeRepository
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
    abstract fun bindSearchSuggestionRepository(
        impl: SearchSuggestionRepositoryImpl
    ): SearchSuggestionRepository

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
        impl: com.sonicmusic.app.features.recommendations.data.UserTasteRepositoryImpl
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
        impl: com.sonicmusic.app.features.recommendations.data.QuickPicksRepositoryImpl
    ): com.sonicmusic.app.domain.repository.QuickPicksRepository

    @Binds
    @Singleton
    abstract fun bindLyricsRepository(
        impl: LyricsRepositoryImpl
    ): LyricsRepository

    @Binds
    @Singleton
    abstract fun bindBrowseRepository(
        impl: com.sonicmusic.app.features.charts.data.BrowseRepositoryImpl
    ): com.sonicmusic.app.domain.repository.BrowseRepository

    @Binds
    @Singleton
    abstract fun bindAlbumRepository(
        impl: AlbumRepositoryImpl
    ): AlbumRepository

    @Binds
    @Singleton
    abstract fun bindChartsRepository(
        impl: com.sonicmusic.app.features.charts.data.ChartsRepositoryImpl
    ): com.sonicmusic.app.domain.repository.ChartsRepository

    @Binds
    @Singleton
    abstract fun bindPlayerRepository(
        impl: PlayerRepositoryImpl
    ): PlayerRepository

    @Binds
    @Singleton
    abstract fun bindHomeRepository(
        impl: InnertubeHomeRepository
    ): HomeRepository

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