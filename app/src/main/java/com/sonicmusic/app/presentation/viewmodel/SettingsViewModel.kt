package com.sonicmusic.app.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.data.repository.SettingsRepository
import com.sonicmusic.app.domain.model.FullPlayerStyle
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.model.ThemeMode
import com.sonicmusic.app.domain.repository.RecentSearchRepository
import com.sonicmusic.app.service.AudioEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val regionRepository: com.sonicmusic.app.data.repository.RegionRepository,
    private val audioEngine: AudioEngine,
    private val recentSearchRepository: RecentSearchRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // Audio Engine Settings
    val wifiStreamingQuality = audioEngine.audioEngineState
        .map { it.wifiQualityTier }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamQuality.LOSSLESS)

    val cellularStreamingQuality = audioEngine.audioEngineState
        .map { it.cellularQualityTier }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamQuality.HIGH)

    val downloadQuality = audioEngine.audioEngineState
        .map { it.downloadQualityTier }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamQuality.LOSSLESS)

    val normalizeVolume = audioEngine.audioEngineState
        .map { it.soundCheckEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val crossfadeDuration = audioEngine.audioEngineState
        .map { state ->
            if (state.crossfadeEnabled) {
                (state.crossfadeDuration / 1000).coerceIn(0, 12)
            } else {
                0
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val enhancedAudio = audioEngine.audioEngineState
        .map { it.enhancedAudioEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Repository Settings
    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    val fullPlayerStyle: StateFlow<FullPlayerStyle> = settingsRepository.fullPlayerStyle
        .stateIn(viewModelScope, SharingStarted.Eagerly, FullPlayerStyle.NORMAL)

    val dynamicColors: StateFlow<Boolean> = settingsRepository.dynamicColors
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val dynamicColorIntensity: StateFlow<Int> = settingsRepository.dynamicColorIntensity
        .stateIn(viewModelScope, SharingStarted.Eagerly, 85)

    val gaplessPlayback: StateFlow<Boolean> = settingsRepository.gaplessPlayback
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val cacheSizeLimit: StateFlow<Long> = settingsRepository.cacheSizeLimit
        .stateIn(viewModelScope, SharingStarted.Eagerly, 2048L)

    val pauseHistory: StateFlow<Boolean> = settingsRepository.pauseHistory
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val albumArtBlur: StateFlow<Boolean> = settingsRepository.albumArtBlur
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val skipSilence: StateFlow<Boolean> = settingsRepository.skipSilence
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val autoQueueSimilar: StateFlow<Boolean> = settingsRepository.autoQueueSimilar
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
        
    // Region Settings
    val regionCode: StateFlow<String?> = regionRepository.regionCode
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
        
    val countryCode: StateFlow<String?> = regionRepository.countryCode
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val countryName: StateFlow<String?> = regionRepository.countryName
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Cache size tracking
    private val _cacheSize = MutableStateFlow("Calculating...")
    val cacheSize: StateFlow<String> = _cacheSize.asStateFlow()

    // App version
    val appVersion: String = try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        "${packageInfo.versionName} (Build ${packageInfo.longVersionCode})"
    } catch (e: Exception) {
        "1.0.0"
    }

    init {
        calculateCacheSize()
    }

    fun setWifiQuality(quality: StreamQuality) = audioEngine.setWifiQualityTier(quality)
    
    fun setCellularQuality(quality: StreamQuality) = audioEngine.setCellularQualityTier(quality)

    fun setDownloadQuality(quality: StreamQuality) {
        viewModelScope.launch {
            // Keep both stores in sync for compatibility with existing app flows.
            settingsRepository.setDownloadQuality(quality)
            audioEngine.setDownloadQualityTier(quality)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    fun setDynamicColors(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDynamicColors(enabled)
        }
    }

    fun setDynamicColorIntensity(value: Int) {
        viewModelScope.launch {
            settingsRepository.setDynamicColorIntensity(value)
        }
    }

    fun setFullPlayerStyle(style: FullPlayerStyle) {
        viewModelScope.launch {
            settingsRepository.setFullPlayerStyle(style)
        }
    }

    fun setEnhancedAudio(enabled: Boolean) {
        audioEngine.setEnhancedAudio(enabled)
    }

    fun setNormalizeVolume(enabled: Boolean) {
        audioEngine.setSoundCheck(enabled)
        viewModelScope.launch {
            settingsRepository.setNormalizeVolume(enabled)
        }
    }

    fun setGaplessPlayback(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setGaplessPlayback(enabled)
        }
    }

    fun setCrossfadeDuration(seconds: Int) {
        val safeSeconds = seconds.coerceIn(0, 12)
        audioEngine.setCrossfade(safeSeconds > 0, safeSeconds * 1000)
        viewModelScope.launch {
            settingsRepository.setCrossfadeDuration(safeSeconds)
        }
    }

    fun setCacheSizeLimit(mb: Long) {
        viewModelScope.launch {
            settingsRepository.setCacheSizeLimit(mb)
        }
    }

    fun setPauseHistory(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPauseHistory(enabled)
        }
    }

    fun setAlbumArtBlur(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAlbumArtBlur(enabled)
        }
    }

    fun setSkipSilence(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSkipSilence(enabled)
        }
    }

    fun setAutoQueueSimilar(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoQueueSimilar(enabled)
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            // Clear app cache directory
            try {
                context.cacheDir?.let { cacheDir ->
                    cacheDir.listFiles()?.forEach { file ->
                        deleteRecursively(file)
                    }
                }

                // Clear external cache if available
                context.externalCacheDir?.let { externalCache ->
                    externalCache.listFiles()?.forEach { file ->
                        deleteRecursively(file)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // Recalculate cache size after clearing
            calculateCacheSize()
        }
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            recentSearchRepository.clearAllSearches()
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            settingsRepository.resetToDefaults()
            audioEngine.resetToDefault()
            regionRepository.initializeRegion()
            calculateCacheSize()
        }
    }

    private fun calculateCacheSize() {
        viewModelScope.launch {
            try {
                var totalSize = 0L
                context.cacheDir?.let { totalSize += getDirSize(it) }
                context.externalCacheDir?.let { totalSize += getDirSize(it) }
                _cacheSize.value = formatFileSize(totalSize)
            } catch (e: Exception) {
                _cacheSize.value = "Unknown"
            }
        }
    }

    private fun getDirSize(dir: File): Long {
        var size = 0L
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                size += if (file.isDirectory) getDirSize(file) else file.length()
            }
        }
        return size
    }

    fun refreshRegion() {
        viewModelScope.launch {
            regionRepository.refreshRegion()
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
