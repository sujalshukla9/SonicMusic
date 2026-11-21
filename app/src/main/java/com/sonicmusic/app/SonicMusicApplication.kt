package com.sonicmusic.app

import android.app.ActivityManager
import android.app.Application
import android.os.Build
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
import com.sonicmusic.app.core.updater.UpdateDownloader
import com.sonicmusic.app.features.updates.worker.AutoUpdateWorker
import com.sonicmusic.app.features.updates.worker.CacheCleanupWorker
import com.sonicmusic.app.features.library.data.YouTubeMusicSyncWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
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
        
        applicationScope.launch {
            scheduleAutoUpdate()
            UpdateDownloader(this@SonicMusicApplication).resumePending()
            CacheCleanupWorker.schedule(this@SonicMusicApplication)
            YouTubeMusicSyncWorker.schedule(this@SonicMusicApplication)
        }

        if (BuildConfig.DEBUG) {
            setupStrictMode()
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
            android.util.Log.e("Memory", " Failed to enqueue WorkManager during startup: ${e.message}")
        }
    }

    private fun setupStrictMode() {
        val threadPolicy = android.os.StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .detectCustomSlowCalls()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Formatting diagnostic stacks on the main thread can amplify startup stalls.
            threadPolicy.penaltyListener(Dispatchers.IO.asExecutor()) { violation ->
                android.util.Log.w("StrictMode", "Thread policy violation", violation)
            }
        } else {
            threadPolicy.penaltyLog()
        }
        android.os.StrictMode.setThreadPolicy(threadPolicy.build())

        android.os.StrictMode.setVmPolicy(
            android.os.StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .detectLeakedRegistrationObjects()
                .detectFileUriExposure()
                .detectContentUriWithoutPermission()
                .setClassInstanceLimit(com.sonicmusic.app.features.player.service.PlaybackService::class.java, 1)
                .penaltyLog()
                .build()
        )
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

        // Detect low-RAM devices to reduce memory pressure
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val isLowRam = activityManager.isLowRamDevice
        val memoryCachePercent = if (
             isLowRam) 0.10 else 0.20

        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(memoryCachePercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(imageCacheMb * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            // Disable hardware bitmaps on API 26-27 (known rendering bugs on early Oreo)
            .allowHardware(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            // Note: RGB565 removed — causes visible banding on album art gradients.
            // Memory is already controlled by the memoryCachePercent limit above.
            // YouTube sends aggressive no-cache headers; ignore them to use our local cache
            .respectCacheHeaders(false)
            .build()
    }
}
