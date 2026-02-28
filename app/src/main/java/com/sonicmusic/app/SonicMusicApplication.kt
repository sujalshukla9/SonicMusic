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
import com.sonicmusic.app.worker.TelemetryUploadWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class SonicMusicApplication : Application(), ImageLoaderFactory, Configuration.Provider {
    
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // Application-scoped coroutine scope — lives as long as the process.
    // Uses SupervisorJob so child failures don't cancel the whole scope.
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
            
    override fun onCreate() {
        super.onCreate()
        
        // Offload WorkManager scheduling to prevent blocking the UI thread.
        applicationScope.launch {
            scheduleAutoUpdate()
            scheduleTelemetryUpload()
        }

        if (BuildConfig.DEBUG) {
            setupStrictMode()
            setupJankDetector()
            System.setProperty("kotlinx.coroutines.debug", "on")
            System.setProperty("kotlinx.coroutines.stacktrace.recovery", "true")
        }
    }

    private fun scheduleTelemetryUpload() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
            
        val telemetryWorkRequest = PeriodicWorkRequestBuilder<TelemetryUploadWorker>(
            6, TimeUnit.HOURS,
            30, TimeUnit.MINUTES // Flex interval
        )
            .setConstraints(constraints)
            .build()
            
        try {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "TelemetryUploadWorker",
                ExistingPeriodicWorkPolicy.KEEP,
                telemetryWorkRequest
            )
        } catch (e: Exception) {
            android.util.Log.e("Telemetry", "Failed to enqueue WorkManager during startup: ${e.message}")
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
            
        // Use try-catch here as WorkManager SQLite writes can throw on slow disk API 26 devices
        try {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "AutoUpdateWorker",
                ExistingPeriodicWorkPolicy.KEEP,
                autoUpdateWorkRequest
            )
        } catch (e: Exception) {
            android.util.Log.e("Memory", "Failed to enqueue WorkManager during startup: ${e.message}")
        }
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
            // Coil registers its own ComponentCallbacks2 and will trim
            // its memory cache automatically. No manual intervention needed.
            android.util.Log.w("Memory", "Mem pressure high — Coil will auto-trim memory cache.")
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
            // Note: RGB565 removed — causes visible banding on album art gradients.
            // Memory is already controlled by the 25% memoryCache limit above.
            // YouTube sends aggressive no-cache headers; ignore them to use our local cache
            .respectCacheHeaders(false)
            .build()
    }
}