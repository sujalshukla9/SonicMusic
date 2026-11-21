package com.sonicmusic.app.data.local.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sonicmusic.app.domain.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_state_prefs")

data class SavedPlaybackState(
    val song: Song?,
    val positionMs: Long,
    val queue: List<Song>,
    val queueIndex: Int,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = 0
)

private const val SAVED_QUEUE_LIMIT = 50

internal fun savedQueueWindow(song: Song, queue: List<Song>, index: Int): Pair<List<Song>, Int> {
    val matchingIndex = if (queue.getOrNull(index)?.id == song.id) index else queue.indexOfFirst { it.id == song.id }
    val activeIndex = if (matchingIndex >= 0) matchingIndex else index.coerceIn(0, queue.size)
    val fullQueue = if (matchingIndex >= 0) queue else queue.toMutableList().apply { add(activeIndex, song) }
    val start = (activeIndex - SAVED_QUEUE_LIMIT / 2)
        .coerceIn(0, (fullQueue.size - SAVED_QUEUE_LIMIT).coerceAtLeast(0))
    return fullQueue.subList(start, minOf(start + SAVED_QUEUE_LIMIT, fullQueue.size)) to (activeIndex - start)
}

@Singleton
class PlaybackStateDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PlaybackDataStore"
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        private object Keys {
            val CURRENT_SONG = stringPreferencesKey("current_song_json")
            val CURRENT_SONG_ID = stringPreferencesKey("current_song_id")
            val PLAYBACK_POSITION_MS = longPreferencesKey("playback_position_ms")
            val QUEUE_SONGS = stringPreferencesKey("queue_songs_json")
            val QUEUE_INDEX = intPreferencesKey("queue_index")
            val SHUFFLE_ENABLED = booleanPreferencesKey("shuffle_enabled")
            val REPEAT_MODE = intPreferencesKey("repeat_mode")
            val LAST_UPDATED = longPreferencesKey("last_updated_ms")
        }
    }

    val playbackState: Flow<SavedPlaybackState> = context.playbackDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading playback preferences", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            val songJson = prefs[Keys.CURRENT_SONG]
            val song = songJson?.let {
                runCatching { json.decodeFromString<Song>(it) }.getOrNull()
            }
            val queueJson = prefs[Keys.QUEUE_SONGS]
            val queue = queueJson?.let {
                runCatching { json.decodeFromString<List<Song>>(it) }.getOrNull()
            } ?: emptyList()

            val savedIndex = prefs[Keys.QUEUE_INDEX] ?: 0
            val (restoredQueue, restoredIndex) = if (song == null) queue to savedIndex
            else savedQueueWindow(song, queue, savedIndex)
            SavedPlaybackState(
                song = song,
                positionMs = prefs[Keys.PLAYBACK_POSITION_MS] ?: 0L,
                queue = restoredQueue,
                queueIndex = restoredIndex,
                shuffleEnabled = prefs[Keys.SHUFFLE_ENABLED] ?: false,
                repeatMode = prefs[Keys.REPEAT_MODE] ?: 0
            )
        }.flowOn(Dispatchers.Default)

    suspend fun getSavedPlaybackStateOnce(): SavedPlaybackState? =
        playbackState.firstOrNull()?.takeIf { it.song != null }

    suspend fun savePlaybackState(
        song: Song?,
        positionMs: Long,
        queue: List<Song>,
        queueIndex: Int,
        shuffleEnabled: Boolean = false,
        repeatMode: Int = 0
    ) {
        if (song == null) return
        try {
            val songJson = json.encodeToString(song)
            val (safeQueue, savedIndex) = savedQueueWindow(song, queue, queueIndex)
            val queueJson = json.encodeToString(safeQueue)

            context.playbackDataStore.edit { prefs ->
                prefs[Keys.CURRENT_SONG] = songJson
                prefs[Keys.CURRENT_SONG_ID] = song.id
                prefs[Keys.PLAYBACK_POSITION_MS] = positionMs.coerceAtLeast(0L)
                prefs[Keys.QUEUE_SONGS] = queueJson
                prefs[Keys.QUEUE_INDEX] = savedIndex
                prefs[Keys.SHUFFLE_ENABLED] = shuffleEnabled
                prefs[Keys.REPEAT_MODE] = repeatMode
                prefs[Keys.LAST_UPDATED] = System.currentTimeMillis()
            }
            Log.d(TAG, "Saved playback state: ${song.title} at ${positionMs}ms (queue: ${safeQueue.size})")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save playback state: ${e.message}")
        }
    }

    suspend fun updatePosition(songId: String, positionMs: Long) {
        if (positionMs < 0) return
        try {
            context.playbackDataStore.edit { prefs ->
                val storedSongId = prefs[Keys.CURRENT_SONG_ID] ?: prefs[Keys.CURRENT_SONG]?.let {
                    runCatching { json.decodeFromString<Song>(it).id }.getOrNull()
                }
                if (storedSongId != songId) return@edit
                prefs[Keys.CURRENT_SONG_ID] = songId
                prefs[Keys.PLAYBACK_POSITION_MS] = positionMs
                prefs[Keys.LAST_UPDATED] = System.currentTimeMillis()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update position: ${e.message}")
        }
    }

    suspend fun clearPlaybackState() {
        try {
            context.playbackDataStore.edit { prefs ->
                prefs.clear()
            }
            Log.d(TAG, "Cleared playback state")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear playback state: ${e.message}")
        }
    }
}
