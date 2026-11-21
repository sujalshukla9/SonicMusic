package com.sonicmusic.app.data.local.cache

import android.content.Context
import android.util.AtomicFile
import com.sonicmusic.app.data.remote.source.homeDigest
import com.sonicmusic.app.domain.repository.HomeFeedScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

@Singleton
class YtmJsonCache @Inject constructor(@ApplicationContext context: Context) {
    data class Entry<T>(val data: T, val checkedAt: Long) {
        fun isFresh(maxAge: Long): Boolean = System.currentTimeMillis() - checkedAt in 0L until maxAge
    }

    private val root = File(context.noBackupFilesDir, "ytm")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    suspend fun <T> read(scope: HomeFeedScope, name: String, serializer: KSerializer<T>): Entry<T>? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val file = file(scope, name)
                if (System.currentTimeMillis() - file.lastModified() > RETENTION_MS) return@withLock null
                val bytes = readBytes(file) ?: return@withLock null
                try {
                    Entry(json.decodeFromString(serializer, bytes.toString(Charsets.UTF_8)), file.lastModified())
                } catch (_: kotlinx.serialization.SerializationException) {
                    null
                }
            }
        }

    suspend fun <T> write(
        scope: HomeFeedScope,
        name: String,
        serializer: KSerializer<T>,
        data: T,
        checkedAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        val bytes = json.encodeToString(serializer, data).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) return@withContext
        lock.withLock {
            val file = file(scope, name)
            val directory = checkNotNull(file.parentFile)
            if (!directory.isDirectory && !directory.mkdirs()) return@withLock
            if (readBytes(file)?.contentEquals(bytes) != true) {
                val atomic = AtomicFile(file)
                val stream = atomic.startWrite()
                try {
                    stream.write(bytes)
                    atomic.finishWrite(stream)
                } catch (e: Exception) {
                    atomic.failWrite(stream)
                    throw e
                }
            }
            file.setLastModified(checkedAt)
            pruneExpired()
        }
    }

    private fun file(scope: HomeFeedScope, name: String): File {
        require(NAME.matches(name))
        val key = homeDigest("${scope.accountKey}\n${scope.region}\n${scope.language}")
        return File(File(root, key), "$name.json")
    }

    private fun readBytes(file: File): ByteArray? = try {
        AtomicFile(file).openRead().use { input ->
            if (input.channel.size() > MAX_FILE_BYTES) null else input.readBytes()
        }
    } catch (_: FileNotFoundException) {
        null
    }

    private var lastPrune = 0L

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        if (now - lastPrune < PRUNE_INTERVAL_MS) return
        lastPrune = now
        root.listFiles()?.filter(File::isDirectory)?.forEach { directory ->
            directory.listFiles()?.filter { it.extension == "json" && now - it.lastModified() > RETENTION_MS }
                ?.forEach { AtomicFile(it).delete() }
            directory.delete()
        }
    }

    private companion object {
        val NAME = Regex("[a-z0-9_-]{1,100}")
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        const val PRUNE_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
