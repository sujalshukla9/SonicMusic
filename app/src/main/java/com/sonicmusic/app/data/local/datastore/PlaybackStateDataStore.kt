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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
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

            SavedPlaybackState(
                song = song,
                positionMs = prefs[Keys.PLAYBACK_POSITION_MS] ?: 0L,
                queue = queue,
                queueIndex = prefs[Keys.QUEUE_INDEX] ?: 0,
                shuffleEnabled = prefs[Keys.SHUFFLE_ENABLED] ?: false,
                repeatMode = prefs[Keys.REPEAT_MODE] ?: 0
            )
        }

    suspend fun getSavedPlaybackStateOnce(): SavedPlaybackState? {
        return runCatching {
            val prefs = context.playbackDataStore.data
                .catch { emit(emptyPreferences()) }
                .firstOrNull() ?: return null

            val songJson = prefs[Keys.CURRENT_SONG] ?: return null
            val song = runCatching { json.decodeFromString<Song>(songJson) }.getOrNull() ?: return null
            val queueJson = prefs[Keys.QUEUE_SONGS]
            val queue = queueJson?.let {
                runCatching { json.decodeFromString<List<Song>>(it) }.getOrNull()
            } ?: listOf(song)

            SavedPlaybackState(
                song = song,
                positionMs = prefs[Keys.PLAYBACK_POSITION_MS] ?: 0L,
                queue = queue,
                queueIndex = prefs[Keys.QUEUE_INDEX] ?: 0,
                shuffleEnabled = prefs[Keys.SHUFFLE_ENABLED] ?: false,
                repeatMode = prefs[Keys.REPEAT_MODE] ?: 0
            )
        }.getOrNull()
    }

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
            val safeQueue = queue.take(50)
            val queueJson = json.encodeToString(safeQueue)

            context.playbackDataStore.edit { prefs ->
                prefs[Keys.CURRENT_SONG] = songJson
                prefs[Keys.PLAYBACK_POSITION_MS] = positionMs.coerceAtLeast(0L)
                prefs[Keys.QUEUE_SONGS] = queueJson
                prefs[Keys.QUEUE_INDEX] = queueIndex.coerceIn(0, (safeQueue.size - 1).coerceAtLeast(0))
                prefs[Keys.SHUFFLE_ENABLED] = shuffleEnabled
                prefs[Keys.REPEAT_MODE] = repeatMode
                prefs[Keys.LAST_UPDATED] = System.currentTimeMillis()
            }
            Log.d(TAG, "Saved playback state: ${song.title} at ${positionMs}ms (queue: ${safeQueue.size})")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save playback state: ${e.message}")
        }
    }

    suspend fun updatePosition(positionMs: Long) {
        if (positionMs < 0) return
        try {
            context.playbackDataStore.edit { prefs ->
                prefs[Keys.PLAYBACK_POSITION_MS] = positionMs
                prefs[Keys.LAST_UPDATED] = System.currentTimeMillis()
            }
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
