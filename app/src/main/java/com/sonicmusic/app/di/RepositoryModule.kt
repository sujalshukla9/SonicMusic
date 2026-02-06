package com.sonicmusic.app.di

import com.sonicmusic.app.data.repository.SongRepositoryImpl
import com.sonicmusic.app.domain.repository.SongRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSongRepository(
        songRepositoryImpl: SongRepositoryImpl
    ): SongRepository
    
    @Binds
    @Singleton
    abstract fun bindHistoryRepository(
        impl: com.sonicmusic.app.data.repository.HistoryRepositoryImpl
    ): com.sonicmusic.app.domain.repository.HistoryRepository

    @Binds
    @Singleton
    abstract fun bindRecentSearchRepository(
        impl: com.sonicmusic.app.data.repository.RecentSearchRepositoryImpl
    ): com.sonicmusic.app.domain.repository.RecentSearchRepository

    @Binds
    @Singleton
    abstract fun bindPlaylistRepository(
        impl: com.sonicmusic.app.data.repository.PlaylistRepositoryImpl
    ): com.sonicmusic.app.domain.repository.PlaylistRepository

    @Binds
    @Singleton
    abstract fun bindCacheRepository(
        impl: com.sonicmusic.app.data.repository.CacheRepositoryImpl
    ): com.sonicmusic.app.domain.repository.CacheRepository

    @Binds
    @Singleton
    abstract fun bindRecommendationService(
        impl: com.sonicmusic.app.data.service.RecommendationServiceImpl
    ): com.sonicmusic.app.domain.service.RecommendationService
    
    @Binds
    @Singleton
    abstract fun bindPlayerController(
        impl: com.sonicmusic.app.data.service.PlayerControllerImpl
    ): com.sonicmusic.app.domain.service.PlayerController
}
