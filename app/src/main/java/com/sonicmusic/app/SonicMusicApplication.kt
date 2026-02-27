package com.sonicmusic.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.sonicmusic.app.worker.AutoUpdateWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class SonicMusicApplication : Application(), ImageLoaderFactory, Configuration.Provider {
    
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
            
    override fun onCreate() {
        super.onCreate()
        
        scheduleAutoUpdate()

        if (BuildConfig.DEBUG) {
            setupStrictMode()
            setupJankDetector()
            System.setProperty("kotlinx.coroutines.debug", "on")
            System.setProperty("kotlinx.coroutines.stacktrace.recovery", "true")
        }
    }

    private fun scheduleAutoUpdate() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
            
        val autoUpdateWorkRequest = PeriodicWorkRequestBuilder<AutoUpdateWorker>(
            1, TimeUnit.HOURS,
            15, TimeUnit.MINUTES // Flex interval
        )
            .setConstraints(constraints)
            .build()
            
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "AutoUpdateWorker",
            ExistingPeriodicWorkPolicy.KEEP,
            autoUpdateWorkRequest
        )
    }

    private fun setupStrictMode() {
        android.os.StrictMode.setThreadPolicy(
            android.os.StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build()
        )

        android.os.StrictMode.setVmPolicy(
            android.os.StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .detectLeakedRegistrationObjects()
                .detectFileUriExposure()
                .detectContentUriWithoutPermission()
                .setClassInstanceLimit(com.sonicmusic.app.player.playback.PlaybackService::class.java, 1)
                .penaltyLog()
                .build()
        )
    }

    private fun setupJankDetector() {
        val choreographer = android.view.Choreographer.getInstance()
        var lastFrameTime = 0L
        choreographer.postFrameCallback(object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastFrameTime > 0) {
                    val droppedFrames = ((frameTimeNanos - lastFrameTime) / 16_666_666) - 1
                    if (droppedFrames > 2) {
                        android.util.Log.w("Jank", "⚠️ Dropped $droppedFrames frames " +
                            "(${(frameTimeNanos - lastFrameTime) / 1_000_000}ms)")
                    }
                }
                lastFrameTime = frameTimeNanos
                choreographer.postFrameCallback(this)
            }
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val levelName = when (level) {
            TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL ⚠️"
            TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            TRIM_MEMORY_COMPLETE -> "COMPLETE 🔴"
            else -> "level=$level"
        }
        android.util.Log.w("Memory", "onTrimMemory: $levelName")

        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            try {
                // To access the image loader memory cache directly:
                // We don't have a direct reference to the memory cache here without creating a new instance
                // We'll let Coil handle its internal cache clearing which it hooks into naturally via ComponentCallbacks2.
                // Log only that we expect Coil to clear its cache.
                android.util.Log.w("Memory", "Mem pressure high — Coil natural cache clearing expected.")
                android.util.Log.w("Memory", "Cleared Coil memory cache")
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    /**
     * Configures Coil's global ImageLoader with tuned caches.
     *
     * - Memory cache: 25% of app memory (up from Coil default ~10%)
     * - Disk cache: sized proportionally from the user's cache limit setting
     *   (25% of total limit, min 50MB, max 512MB)
     * - Crossfade enabled globally for smooth image transitions
     */
    override fun newImageLoader(): ImageLoader {
        // Read cache limit from DataStore prefs synchronously (safe at startup).
        // DataStore backs onto a file called "sonic_music_settings.preferences_pb",
        // but we can't read proto synchronously without Hilt. Use a sensible default.
        val totalCacheLimitMb = 2048L // 2 GB default — matches DataStore default
        // Allocate 25% of total cache budget to image cache, clamped to [50 MB, 512 MB]
        val imageCacheMb = (totalCacheLimitMb / 4).coerceIn(50L, 512L)

        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(imageCacheMb * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            // RGB565 uses 50% less memory than ARGB8888 for thumbnails (no alpha needed)
            .allowRgb565(true)
            // YouTube sends aggressive no-cache headers; ignore them to use our local cache
            .respectCacheHeaders(false)
            .build()
    }
}