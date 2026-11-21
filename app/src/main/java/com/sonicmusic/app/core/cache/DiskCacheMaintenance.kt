package com.sonicmusic.app.core.cache

import android.content.Context
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.sonicmusic.app.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@Singleton
class DiskCacheMaintenance @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val httpClient: dagger.Lazy<OkHttpClient>
) {
    private val operation = Mutex()

    @OptIn(ExperimentalCoilApi::class)
    suspend fun trimAndMeasure(clear: Boolean = false): Long = operation.withLock {
        val maxBytes = settings.cacheSizeLimit.first() * 1024L * 1024L
        withContext(Dispatchers.IO) {
            val cacheDir = context.cacheDir
            val roots = listOfNotNull(cacheDir, context.externalCacheDir).distinct()
            val managed = setOf(File(cacheDir, "image_cache"), File(cacheDir, "http_cache"))

            if (clear) {
                context.imageLoader.diskCache?.clear()
                httpClient.get().cache?.evictAll()
                for (root in roots) {
                    root.listFiles()?.filterNot(managed::contains)?.forEach { it.deleteRecursively() }
                }
            }

            suspend fun totalBytes(): Long {
                val coroutine = currentCoroutineContext()
                return roots.sumOf { root ->
                    root.walkTopDown().sumOf { file ->
                        coroutine.ensureActive()
                        if (file.isFile) file.length() else 0L
                    }
                }
            }

            var bytes = totalBytes()
            if (!clear && maxBytes > 0 && bytes > maxBytes) {
                val removable = roots.asSequence()
                    .flatMap { it.walkTopDown().onEnter { folder -> folder !in managed } }
                    .filter(File::isFile)
                    .map { file -> file to file.lastModified() }
                    .sortedBy { it.second }
                val coroutine = currentCoroutineContext()
                for ((file, _) in removable) {
                    coroutine.ensureActive()
                    if (bytes <= maxBytes) break
                    val size = file.length()
                    if (file.delete()) bytes -= size
                }
                if (bytes > maxBytes) {
                    httpClient.get().cache?.evictAll()
                    bytes = totalBytes()
                    if (bytes > maxBytes) {
                        context.imageLoader.diskCache?.clear()
                        bytes = totalBytes()
                    }
                }
            }
            bytes
        }
    }
}
