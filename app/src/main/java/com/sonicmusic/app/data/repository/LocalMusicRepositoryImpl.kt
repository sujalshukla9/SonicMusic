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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMusicRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localSongDao: LocalSongDao
) : LocalMusicRepository {

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
            MediaStore.Audio.Media.SIZE
        )

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0"
        } else {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        }

        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "Unknown Title"
                val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                val album = cursor.getString(albumColumn)
                val duration = cursor.getInt(durationColumn) / 1000 // Convert to seconds
                val filePath = cursor.getString(dataColumn) ?: ""
                val albumId = cursor.getLong(albumIdColumn)
                val dateAdded = cursor.getLong(dateAddedColumn)
                val fileSize = cursor.getLong(sizeColumn)

                // Skip very short files (likely not music)
                if (duration < 30) continue

                songs.add(
                    LocalSong(
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
                )
            }
        }

        return songs
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