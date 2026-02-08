package com.sonicmusic.app.data.repository

import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) {
    val streamQuality: Flow<StreamQuality> = settingsDataStore.streamQuality
    val downloadQuality: Flow<StreamQuality> = settingsDataStore.downloadQuality
    val themeMode: Flow<ThemeMode> = settingsDataStore.themeMode
    val dynamicColors: Flow<Boolean> = settingsDataStore.dynamicColors
    val normalizeVolume: Flow<Boolean> = settingsDataStore.normalizeVolume
    val gaplessPlayback: Flow<Boolean> = settingsDataStore.gaplessPlayback
    val crossfadeDuration: Flow<Int> = settingsDataStore.crossfadeDuration
    val cacheSizeLimit: Flow<Long> = settingsDataStore.cacheSizeLimit
    val pauseHistory: Flow<Boolean> = settingsDataStore.pauseHistory
    val albumArtBlur: Flow<Boolean> = settingsDataStore.albumArtBlur
    val skipSilence: Flow<Boolean> = settingsDataStore.skipSilence
    val autoQueueSimilar: Flow<Boolean> = settingsDataStore.autoQueueSimilar

    suspend fun setStreamQuality(quality: StreamQuality) {
        settingsDataStore.setStreamQuality(quality)
    }

    suspend fun setDownloadQuality(quality: StreamQuality) {
        settingsDataStore.setDownloadQuality(quality)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        settingsDataStore.setThemeMode(mode)
    }

    suspend fun setDynamicColors(enabled: Boolean) {
        settingsDataStore.setDynamicColors(enabled)
    }

    suspend fun setNormalizeVolume(enabled: Boolean) {
        settingsDataStore.setNormalizeVolume(enabled)
    }

    suspend fun setGaplessPlayback(enabled: Boolean) {
        settingsDataStore.setGaplessPlayback(enabled)
    }

    suspend fun setCrossfadeDuration(seconds: Int) {
        settingsDataStore.setCrossfadeDuration(seconds)
    }

    suspend fun setCacheSizeLimit(mb: Long) {
        settingsDataStore.setCacheSizeLimit(mb)
    }

    suspend fun setPauseHistory(enabled: Boolean) {
        settingsDataStore.setPauseHistory(enabled)
    }

    suspend fun setAlbumArtBlur(enabled: Boolean) {
        settingsDataStore.setAlbumArtBlur(enabled)
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        settingsDataStore.setSkipSilence(enabled)
    }

    suspend fun setAutoQueueSimilar(enabled: Boolean) {
        settingsDataStore.setAutoQueueSimilar(enabled)
    }
}