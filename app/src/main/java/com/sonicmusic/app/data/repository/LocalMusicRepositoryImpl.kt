package com.sonicmusic.app.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.sonicmusic.app.data.local.dao.LocalSongDao
import com.sonicmusic.app.data.local.entity.LocalSongEntity
import com.sonicmusic.app.domain.model.LocalSong
import com.sonicmusic.app.domain.repository.LocalMusicRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMusicRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localSongDao: LocalSongDao
) : LocalMusicRepository {
    companion object {
        // Includes common music/container extensions seen on Android devices.
        private val SUPPORTED_AUDIO_EXTENSIONS = setOf(
            "mp3", "aac", "m4a", "m4b", "flac", "wav", "wave", "ogg", "oga", "opus", "webm",
            "alac", "aif", "aiff", "aifc", "amr", "3gp", "3gpp", "3g2", "mp2", "mka", "wma",
            "ape", "wv", "ac3", "eac3", "dts", "mid", "midi"
        )
    }

    override fun getLocalSongs(): Flow<List<LocalSong>> {
        return localSongDao.getAllLocalSongs().map { entities ->
            entities.map { it.toLocalSong() }
        }
    }

    override suspend fun scanDeviceMusic(): Result<List<LocalSong>> {
        return withContext(Dispatchers.IO) {
            try {
                val songs = scanMediaStore()
                
                // Save to database
                val entities = songs.map { it.toEntity() }
                localSongDao.deleteAll()
                localSongDao.insertAll(entities)
                
                Result.success(songs)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun refreshLocalMusic() {
        scanDeviceMusic()
    }

    @Suppress("DEPRECATION")
    private fun scanMediaStore(): List<LocalSong> {
        val songs = mutableListOf<LocalSong>()
        val contentResolver: ContentResolver = context.contentResolver

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DISPLAY_NAME
        )

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            buildString {
                append("(")
                append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
                append(" OR ${MediaStore.Audio.Media.MIME_TYPE} LIKE ?")
                append(" OR ${MediaStore.Audio.Media.MIME_TYPE} LIKE ?")
                append(")")
                append(" AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
                append(" AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0")
                append(" AND ${MediaStore.Audio.Media.SIZE} > 0")
            }
        } else {
            buildString {
                append("(")
                append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
                append(" OR ${MediaStore.Audio.Media.MIME_TYPE} LIKE ?")
                append(" OR ${MediaStore.Audio.Media.MIME_TYPE} LIKE ?")
                append(")")
                append(" AND ${MediaStore.Audio.Media.SIZE} > 0")
            }
        }
        val selectionArgs = arrayOf("audio/%", "application/ogg")

        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val albumIdColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeTypeColumn = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
            val displayNameColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "Unknown Title"
                val artistRaw = cursor.getString(artistColumn).orEmpty()
                val artist = if (artistRaw.isBlank() || artistRaw == "<unknown>") "Unknown Artist" else artistRaw
                val album = cursor.getString(albumColumn)
                val durationMs = cursor.getLong(durationColumn)
                val duration = (durationMs / 1000L).toInt().coerceAtLeast(0)
                val rawPath = if (dataColumn >= 0) cursor.getString(dataColumn).orEmpty() else ""
                val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val mimeType = if (mimeTypeColumn >= 0) cursor.getString(mimeTypeColumn).orEmpty() else ""
                val displayName = if (displayNameColumn >= 0) cursor.getString(displayNameColumn).orEmpty() else ""
                val playableSource = rawPath.takeIf { it.isNotBlank() } ?: contentUri.toString()
                val albumId = if (albumIdColumn >= 0) cursor.getLong(albumIdColumn) else 0L
                val dateAdded = cursor.getLong(dateAddedColumn)
                val fileSize = cursor.getLong(sizeColumn)

                if (!isSupportedAudioFormat(mimeType = mimeType, rawPath = rawPath, displayName = displayName)) {
                    continue
                }

                // Skip only extremely short clips/noise.
                if (duration < 5) continue

                songs.add(
                    LocalSong(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        duration = duration,
                        filePath = playableSource,
                        albumId = if (albumId > 0L) albumId else null,
                        dateAdded = dateAdded,
                        fileSize = fileSize
                    )
                )
            }
        }

        return songs
    }

    private fun isSupportedAudioFormat(
        mimeType: String?,
        rawPath: String?,
        displayName: String?
    ): Boolean {
        val normalizedMime = mimeType.orEmpty().lowercase(Locale.US)
        if (normalizedMime.startsWith("audio/")) return true
        if (normalizedMime == "application/ogg") return true

        val extension = extensionFromPath(rawPath).ifBlank { extensionFromPath(displayName) }
        return extension in SUPPORTED_AUDIO_EXTENSIONS
    }

    private fun extensionFromPath(value: String?): String {
        val name = value.orEmpty().substringAfterLast('/', value.orEmpty())
        if (!name.contains('.')) return ""
        return name.substringAfterLast('.', "").lowercase(Locale.US)
    }

    fun getAlbumArtUri(albumId: Long): Uri? {
        return ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            albumId
        )
    }

    private fun LocalSong.toEntity(): LocalSongEntity {
        return LocalSongEntity(
            id = id,
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            filePath = filePath,
            albumId = albumId,
            dateAdded = dateAdded,
            fileSize = fileSize
        )
    }

    private fun LocalSongEntity.toLocalSong(): LocalSong {
        return LocalSong(
            id = id,
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            filePath = filePath,
            albumId = albumId,
            dateAdded = dateAdded,
            fileSize = fileSize
        )
    }
}
