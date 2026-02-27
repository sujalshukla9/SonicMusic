package com.sonicmusic.app.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sonicmusic.app.domain.model.FullPlayerStyle
import com.sonicmusic.app.domain.model.PaletteStyle
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.model.ThemeMode
import com.sonicmusic.app.domain.model.DarkMode
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
        val STREAM_QUALITY = intPreferencesKey("stream_quality") // legacy bitrate key
        val STREAM_QUALITY_TIER = stringPreferencesKey("stream_quality_tier")
        val DOWNLOAD_QUALITY = intPreferencesKey("download_quality") // legacy bitrate key
        val DOWNLOAD_QUALITY_TIER = stringPreferencesKey("download_quality_tier")
        val DARK_MODE = stringPreferencesKey("theme_mode") // Reusing old key to migrate dark mode prefs
        val THEME_MODE = stringPreferencesKey("theme_source") // New key for Dynamic theming
        val PALETTE_STYLE = stringPreferencesKey("palette_style")
        val PURE_BLACK = booleanPreferencesKey("pure_black")
        val LAST_SEED_COLOR = intPreferencesKey("last_seed_color")
        val FULL_PLAYER_STYLE = stringPreferencesKey("full_player_style")
        val DYNAMIC_COLORS = booleanPreferencesKey("dynamic_colors") // Legacy
        val DYNAMIC_COLOR_INTENSITY = intPreferencesKey("dynamic_color_intensity") // Legacy
        val NORMALIZE_VOLUME = booleanPreferencesKey("normalize_volume")
        val GAPLESS_PLAYBACK = booleanPreferencesKey("gapless_playback")
        val CROSSFADE_DURATION = intPreferencesKey("crossfade_duration")
        val CACHE_SIZE_LIMIT = longPreferencesKey("cache_size_limit")
        val PAUSE_HISTORY = booleanPreferencesKey("pause_history")
        val ALBUM_ART_BLUR = booleanPreferencesKey("album_art_blur")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val AUTO_QUEUE_SIMILAR = booleanPreferencesKey("auto_queue_similar")
        val REGION_CODE = stringPreferencesKey("region_code")
        val COUNTRY_CODE = stringPreferencesKey("country_code")
        val COUNTRY_NAME = stringPreferencesKey("country_name")
        val BLACKLISTED_SONG_IDS = stringSetPreferencesKey("blacklisted_song_ids")
        val AUTO_UPDATE_ENABLED = booleanPreferencesKey("auto_update_enabled")
    }

    val streamQuality: Flow<StreamQuality> = context.dataStore.data
        .map { preferences ->
            val tier = preferences[PreferencesKeys.STREAM_QUALITY_TIER]
            if (tier != null) {
                StreamQuality.fromName(tier)
            } else {
                val legacyBitrate = preferences[PreferencesKeys.STREAM_QUALITY] ?: StreamQuality.HIGH.bitrate
                StreamQuality.fromBitrate(legacyBitrate)
            }
        }

    val downloadQuality: Flow<StreamQuality> = context.dataStore.data
        .map { preferences ->
            val tier = preferences[PreferencesKeys.DOWNLOAD_QUALITY_TIER]
            if (tier != null) {
                StreamQuality.fromName(tier)
            } else {
                val legacyBitrate = preferences[PreferencesKeys.DOWNLOAD_QUALITY] ?: StreamQuality.BEST.bitrate
                StreamQuality.fromBitrate(legacyBitrate)
            }
        }

    val darkMode: Flow<DarkMode> = context.dataStore.data
        .map { preferences ->
            val modeString = preferences[PreferencesKeys.DARK_MODE] ?: DarkMode.SYSTEM.name
            DarkMode.fromString(modeString)
        }

    val themeMode: Flow<ThemeMode> = context.dataStore.data
        .map { preferences ->
            val modeString = preferences[PreferencesKeys.THEME_MODE] ?: ThemeMode.DYNAMIC.name
            ThemeMode.fromString(modeString)
        }

    val paletteStyle: Flow<PaletteStyle> = context.dataStore.data
        .map { preferences ->
            val styleString = preferences[PreferencesKeys.PALETTE_STYLE] ?: PaletteStyle.CONTENT.name
            try {
                PaletteStyle.valueOf(styleString)
            } catch (e: Exception) {
                PaletteStyle.CONTENT
            }
        }

    val pureBlack: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.PURE_BLACK] ?: false
        }

    val lastSeedColor: Flow<Int?> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.LAST_SEED_COLOR]
        }

    val dynamicColors: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.DYNAMIC_COLORS] ?: true
        }

    val dynamicColorIntensity: Flow<Int> = context.dataStore.data
        .map { preferences ->
            (preferences[PreferencesKeys.DYNAMIC_COLOR_INTENSITY] ?: 85).coerceIn(0, 100)
        }

    val fullPlayerStyle: Flow<FullPlayerStyle> = context.dataStore.data
        .map { preferences ->
            val style = preferences[PreferencesKeys.FULL_PLAYER_STYLE] ?: FullPlayerStyle.NORMAL.name
            FullPlayerStyle.fromString(style)
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
            preferences[PreferencesKeys.CROSSFADE_DURATION] ?: 0
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
            preferences[PreferencesKeys.ALBUM_ART_BLUR] ?: false
        }

    val skipSilence: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.SKIP_SILENCE] ?: false
        }

    val autoQueueSimilar: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.AUTO_QUEUE_SIMILAR] ?: true
        }

    val regionCode: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.REGION_CODE]
        }

    val countryCode: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.COUNTRY_CODE]
        }
    
    val countryName: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.COUNTRY_NAME]
        }

    val blacklistedSongIds: Flow<Set<String>> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.BLACKLISTED_SONG_IDS] ?: emptySet()
        }

    val autoUpdateEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.AUTO_UPDATE_ENABLED] ?: false
        }

    suspend fun setStreamQuality(quality: StreamQuality) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.STREAM_QUALITY_TIER] = quality.name
        }
    }

    suspend fun setDownloadQuality(quality: StreamQuality) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DOWNLOAD_QUALITY_TIER] = quality.name
        }
    }

    suspend fun setDarkMode(mode: DarkMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DARK_MODE] = mode.name
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.THEME_MODE] = mode.name
        }
    }

    suspend fun setPaletteStyle(style: PaletteStyle) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PALETTE_STYLE] = style.name
        }
    }

    suspend fun setPureBlack(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PURE_BLACK] = enabled
        }
    }

    suspend fun setLastSeedColor(color: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_SEED_COLOR] = color
        }
    }

    suspend fun setDynamicColors(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DYNAMIC_COLORS] = enabled
        }
    }

    suspend fun setDynamicColorIntensity(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DYNAMIC_COLOR_INTENSITY] = value.coerceIn(0, 100)
        }
    }

    suspend fun setFullPlayerStyle(style: FullPlayerStyle) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FULL_PLAYER_STYLE] = style.name
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

    suspend fun setRegionCode(region: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.REGION_CODE] = region
        }
    }

    suspend fun setCountryCode(country: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.COUNTRY_CODE] = country
        }
    }

    suspend fun setCountryName(name: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.COUNTRY_NAME] = name
        }
    }

    suspend fun addBlacklistedSongId(songId: String) {
        if (songId.isBlank()) return
        context.dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.BLACKLISTED_SONG_IDS] ?: emptySet()
            preferences[PreferencesKeys.BLACKLISTED_SONG_IDS] = current + songId
        }
    }

    suspend fun removeBlacklistedSongId(songId: String) {
        if (songId.isBlank()) return
        context.dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.BLACKLISTED_SONG_IDS] ?: emptySet()
            preferences[PreferencesKeys.BLACKLISTED_SONG_IDS] = current - songId
        }
    }

    suspend fun setAutoUpdateEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_UPDATE_ENABLED] = enabled
        }
    }

    suspend fun resetAll() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
