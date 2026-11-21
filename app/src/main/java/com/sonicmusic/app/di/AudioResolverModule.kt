package com.sonicmusic.app.di

import com.sonicmusic.app.domain.audio.quality.AudioQualityInspector
import com.sonicmusic.app.domain.audio.quality.DefaultAudioQualityInspector
import com.sonicmusic.app.domain.audio.resolver.AudioResolver
import com.sonicmusic.app.domain.audio.resolver.AudioSourceSelector
import com.sonicmusic.app.domain.audio.resolver.DefaultAudioSourceSelector
import com.sonicmusic.app.domain.audio.resolver.IntelligentAudioResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioResolverModule {

    @Binds
    @Singleton
    abstract fun bindAudioQualityInspector(
        inspector: DefaultAudioQualityInspector
    ): AudioQualityInspector

    @Binds
    @Singleton
    abstract fun bindAudioSourceSelector(
        selector: DefaultAudioSourceSelector
    ): AudioSourceSelector

    @Binds
    @Singleton
    abstract fun bindAudioResolver(
        resolver: IntelligentAudioResolver
    ): AudioResolver
}
