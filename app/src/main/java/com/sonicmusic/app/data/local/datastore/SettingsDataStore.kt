package com.sonicmusic.app.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sonic_music_settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object PreferencesKeys {
        val STREAM_QUALITY = intPreferencesKey("stream_quality")
        val DOWNLOAD_QUALITY = intPreferencesKey("download_quality")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLORS = booleanPreferencesKey("dynamic_colors")
        val NORMALIZE_VOLUME = booleanPreferencesKey("normalize_volume")
        val GAPLESS_PLAYBACK = booleanPreferencesKey("gapless_playback")
        val CROSSFADE_DURATION = intPreferencesKey("crossfade_duration")
        val CACHE_SIZE_LIMIT = longPreferencesKey("cache_size_limit")
        val PAUSE_HISTORY = booleanPreferencesKey("pause_history")
        val ALBUM_ART_BLUR = booleanPreferencesKey("album_art_blur")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val AUTO_QUEUE_SIMILAR = booleanPreferencesKey("auto_queue_similar")
    }

    val streamQuality: Flow<StreamQuality> = context.dataStore.data
        .map { preferences ->
            val bitrate = preferences[PreferencesKeys.STREAM_QUALITY] ?: StreamQuality.HIGH.bitrate
            StreamQuality.fromBitrate(bitrate)
        }

    val downloadQuality: Flow<StreamQuality> = context.dataStore.data
        .map { preferences ->
            val bitrate = preferences[PreferencesKeys.DOWNLOAD_QUALITY] ?: StreamQuality.BEST.bitrate
            StreamQuality.fromBitrate(bitrate)
        }

    val themeMode: Flow<ThemeMode> = context.dataStore.data
        .map { preferences ->
            val modeString = preferences[PreferencesKeys.THEME_MODE] ?: ThemeMode.SYSTEM.name
            ThemeMode.fromString(modeString)
        }

    val dynamicColors: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.DYNAMIC_COLORS] ?: true
        }

    val normalizeVolume: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.NORMALIZE_VOLUME] ?: true
        }

    val gaplessPlayback: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.GAPLESS_PLAYBACK] ?: true
        }

    val crossfadeDuration: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.CROSSFADE_DURATION] ?: 3
        }

    val cacheSizeLimit: Flow<Long> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.CACHE_SIZE_LIMIT] ?: 2048L // 2GB in MB
        }

    val pauseHistory: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.PAUSE_HISTORY] ?: false
        }

    val albumArtBlur: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.ALBUM_ART_BLUR] ?: true
        }

    val skipSilence: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.SKIP_SILENCE] ?: true
        }

    val autoQueueSimilar: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.AUTO_QUEUE_SIMILAR] ?: false
        }

    suspend fun setStreamQuality(quality: StreamQuality) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.STREAM_QUALITY] = quality.bitrate
        }
    }

    suspend fun setDownloadQuality(quality: StreamQuality) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DOWNLOAD_QUALITY] = quality.bitrate
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.THEME_MODE] = mode.name
        }
    }

    suspend fun setDynamicColors(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DYNAMIC_COLORS] = enabled
        }
    }

    suspend fun setNormalizeVolume(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.NORMALIZE_VOLUME] = enabled
        }
    }

    suspend fun setGaplessPlayback(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.GAPLESS_PLAYBACK] = enabled
        }
    }

    suspend fun setCrossfadeDuration(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CROSSFADE_DURATION] = seconds
        }
    }

    suspend fun setCacheSizeLimit(mb: Long) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CACHE_SIZE_LIMIT] = mb
        }
    }

    suspend fun setPauseHistory(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PAUSE_HISTORY] = enabled
        }
    }

    suspend fun setAlbumArtBlur(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ALBUM_ART_BLUR] = enabled
        }
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SKIP_SILENCE] = enabled
        }
    }

    suspend fun setAutoQueueSimilar(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_QUEUE_SIMILAR] = enabled
        }
    }
}