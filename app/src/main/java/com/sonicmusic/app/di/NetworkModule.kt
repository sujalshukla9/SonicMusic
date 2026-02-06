package com.sonicmusic.app.di

import com.sonicmusic.app.data.remote.api.YouTubeiService
import com.sonicmusic.app.data.remote.service.NewPipeDownloader
import com.sonicmusic.app.data.remote.service.YouTubeiServiceImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.schabi.newpipe.extractor.NewPipe
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()
    }

    @Provides
    @Singleton
    fun provideYouTubeiService(client: OkHttpClient): YouTubeiService {
        // Init NewPipe globally
        if (NewPipe.getDownloader() == null) {
            NewPipe.init(NewPipeDownloader(client))
        }
        return YouTubeiServiceImpl()
    }
}
