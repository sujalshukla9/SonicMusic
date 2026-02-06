package com.sonicmusic.app.di

import android.content.Context
import com.sonicmusic.app.SonicMusicApp
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideApplication(@ApplicationContext context: Context): SonicMusicApp {
        return context as SonicMusicApp
    }
}
