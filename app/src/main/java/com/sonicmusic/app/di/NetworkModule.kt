package com.sonicmusic.app.di

import android.content.Context
import com.sonicmusic.app.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Network DI Module — Provides OkHttpClient and API instances.
 *
 * Optimizations applied:
 * - Connection pool: reuses up to 5 idle connections for 30s
 * - HTTP response cache: 10 MB disk cache
 * - Tighter timeouts to reduce ANR risk
 * - retryOnConnectionFailure for transient network errors
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    
    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS
                    else HttpLoggingInterceptor.Level.NONE
        }

        // 10 MB HTTP response cache
        val cacheDir = File(context.cacheDir, "http_cache")
        val cache = Cache(cacheDir, 10L * 1024 * 1024)
        
        val builder = OkHttpClient.Builder()
            .addInterceptor(logging)
            .cache(cache)
            .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
            
        return builder
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
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

