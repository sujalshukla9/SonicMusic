package com.sonicmusic.app.data.remote.source

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class YouTubePlaybackSession private constructor(
    val cookies: String,
    val accountIndex: Int,
    val homeCacheKey: String
) {
    companion object {
        fun parse(input: String, accountIndex: Int): YouTubePlaybackSession? {
            if (accountIndex < 0 || input.length > 16_384 || input.any { it !in ' '..'~' }) return null
            val header = input.trim().let {
                if (it.startsWith("Cookie:", ignoreCase = true)) it.substringAfter(':').trim() else it
            }
            val pairs = header.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            if (pairs.any { !COOKIE_PAIR.matches(it) }) return null
            val signingCookie = pairs.filter { it.startsWith("SAPISID=") }.singleOrNull()
            if (signingCookie.isNullOrEmpty() || signingCookie.substringAfter('=').isEmpty()) return null
            val cacheKey = homeDigest("$accountIndex\n$signingCookie")
            return YouTubePlaybackSession(pairs.joinToString("; "), accountIndex, cacheKey)
        }

        private val COOKIE_PAIR = Regex("""[a-zA-Z0-9_!#$%&'*+.^`|~-]+=[!-:<-~]*""")
    }
}

@Singleton
class YouTubePlaybackSessionStore private constructor(
    openFile: () -> AtomicFile,
    private val keyAlias: String,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        { AtomicFile(File(context.noBackupFilesDir, "youtube_playback_session")) },
        "sonicmusic.youtube.playback"
    )

    internal constructor(file: AtomicFile, keyAlias: String) : this({ file }, keyAlias)

    internal class Snapshot(val revision: Long, val session: YouTubePlaybackSession?)

    private val file by lazy(openFile)
    private val mutex = Mutex()
    private var loaded = false
    @Volatile private var current = Snapshot(0L, null)
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    private val _revisions = MutableStateFlow(0L)
    internal val revisions = _revisions.asStateFlow()

    internal val revision: Long get() = current.revision

    internal suspend fun snapshot(): Snapshot = mutex.withLock {
        if (!loaded) {
            val restored = withContext(Dispatchers.IO) { readSession() }
            current = Snapshot(current.revision, restored)
            _connected.value = restored != null
            loaded = true
        }
        current
    }

    internal suspend fun save(session: YouTubePlaybackSession) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            val encrypted = cipher.doFinal("${session.accountIndex}\n${session.cookies}".toByteArray(Charsets.UTF_8))
            val stream = file.startWrite()
            try {
                stream.write(cipher.iv.size)
                stream.write(cipher.iv)
                stream.write(encrypted)
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
            updateSession(session)
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            keyStore().deleteEntry(keyAlias)
            file.delete()
            updateSession(null)
        }
    }

    private fun updateSession(session: YouTubePlaybackSession?) {
        current = Snapshot(current.revision + 1, session)
        _connected.value = session != null
        _revisions.value = current.revision
        loaded = true
    }

    private fun readSession(): YouTubePlaybackSession? {
        return try {
            val bytes = file.readFully()
            val ivLength = bytes.firstOrNull()?.toInt() ?: return null
            if (ivLength !in 12..16 || bytes.size <= 1 + ivLength) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = bytes.copyOfRange(1, 1 + ivLength)
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, iv))
            val input = cipher.doFinal(bytes, 1 + ivLength, bytes.size - 1 - ivLength).toString(Charsets.UTF_8)
            val accountIndex = input.substringBefore('\n').toIntOrNull() ?: return null
            YouTubePlaybackSession.parse(input.substringAfter('\n'), accountIndex)
        } catch (_: FileNotFoundException) {
            null
        } catch (_: Exception) {
            // An invalidated key or unreadable session requires reconnecting.
            null
        }
    }

    private fun key(create: Boolean): SecretKey {
        val saved = keyStore().getKey(keyAlias, null) as? SecretKey
        if (saved != null) return saved
        if (!create) throw IOException("Playback session key unavailable")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(
                keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
