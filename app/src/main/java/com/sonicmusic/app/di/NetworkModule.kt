package com.sonicmusic.app.di

import com.sonicmusic.app.data.remote.source.AudioEnhancementApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Network DI Module — Provides Retrofit and API instances.
 * 
 * The FFmpeg backend URL should be updated to point to your actual server.
 * Default: localhost for development.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    
    /**
     * Base URL for the FFmpeg transcoding backend.
     * 
     * TODO: Update this to your production backend URL.
     * Examples:
     *   - Local: "http://10.0.2.2:8080/" (Android emulator → host)
     *   - Cloud: "https://sonic-ffmpeg.fly.dev/"
     */
    private const val FFMPEG_BACKEND_URL = "http://10.0.2.2:8080/"
    
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    
    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(FFMPEG_BACKEND_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    @Provides
    @Singleton
    fun provideAudioEnhancementApi(retrofit: Retrofit): AudioEnhancementApi {
        return retrofit.create(AudioEnhancementApi::class.java)
    }

    @Provides
    @Singleton
    fun provideRegionApi(okHttpClient: OkHttpClient): com.sonicmusic.app.data.remote.api.RegionApi {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://ipapi.co/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(com.sonicmusic.app.data.remote.api.RegionApi::class.java)
    }
}
