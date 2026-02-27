package com.sonicmusic.app.presentation.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.core.updater.UpdateDownloader
import com.sonicmusic.app.data.remote.source.AppUpdateService
import com.sonicmusic.app.data.repository.SettingsRepository
import com.sonicmusic.app.domain.model.FullPlayerStyle
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.model.ThemeMode
import com.sonicmusic.app.domain.repository.RecentSearchRepository
import com.sonicmusic.app.player.audio.AudioEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sonicmusic.app.domain.model.DarkMode
import com.sonicmusic.app.domain.model.PaletteStyle
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val regionRepository: com.sonicmusic.app.data.repository.RegionRepository,
    private val audioEngine: AudioEngine,
    private val appUpdateService: AppUpdateService,
    private val recentSearchRepository: RecentSearchRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    data class AppUpdateState(
        val isChecking: Boolean = false,
        val isDownloading: Boolean = false,
        val statusText: String = "Check for the latest app release",
        val isUpdateAvailable: Boolean = false,
        val latestVersion: String? = null,
        val downloadUrl: String? = null
    )

    // Audio Engine Settings
    val wifiStreamingQuality = audioEngine.audioEngineState
        .map { it.wifiQualityTier }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamQuality.BEST)

    val cellularStreamingQuality = audioEngine.audioEngineState
        .map { it.cellularQualityTier }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamQuality.HIGH)

    val downloadQuality = audioEngine.audioEngineState
        .map { it.downloadQualityTier }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamQuality.BEST)

    val normalizeVolume = audioEngine.audioEngineState
        .map { it.soundCheckEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val bassBoostEnabled = audioEngine.audioEngineState
        .map { it.bassBoostEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val songBassEnabled = bassBoostEnabled

    val songBassStrengthPercent = audioEngine.audioEngineState
        .map { state ->
            ((state.bassBoostStrength.coerceIn(0, AudioEngine.MAX_BASS_BOOST_STRENGTH) * 100f) /
                AudioEngine.MAX_BASS_BOOST_STRENGTH).toInt()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 60)

    val crossfadeDuration = audioEngine.audioEngineState
        .map { state ->
            if (state.crossfadeEnabled) {
                (state.crossfadeDuration / 1000).coerceIn(0, 12)
            } else {
                0
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)



    // Repository Settings
    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.DYNAMIC)

    val darkMode: StateFlow<DarkMode> = settingsRepository.darkMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, DarkMode.SYSTEM)

    val paletteStyle: StateFlow<PaletteStyle> = settingsRepository.paletteStyle
        .stateIn(viewModelScope, SharingStarted.Eagerly, PaletteStyle.CONTENT)

    val pureBlack: StateFlow<Boolean> = settingsRepository.pureBlack
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

    val autoUpdateEnabled: StateFlow<Boolean> = settingsRepository.autoUpdateEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _appUpdateState = MutableStateFlow(AppUpdateState())
    val appUpdateState: StateFlow<AppUpdateState> = _appUpdateState.asStateFlow()
        
    // Region Settings
    val regionCode: StateFlow<String?> = regionRepository.regionCode
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
        
    val countryCode: StateFlow<String?> = regionRepository.countryCode
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val countryName: StateFlow<String?> = regionRepository.countryName
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Cache size tracking
    // Cache size tracking
    private val _cacheSize = MutableStateFlow("Calculating...")
    val cacheSize: StateFlow<String> = _cacheSize.asStateFlow()

    // App version
    private val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()

    private val appVersionName: String = packageInfo?.versionName
        ?.takeIf { it.isNotBlank() }
        ?: "1.0.0"

    val appVersion: String = packageInfo?.let { info ->
        val versionCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
        "${info.versionName} (Build $versionCode)"
    } ?: appVersionName

    init {
        calculateCacheSize()
        enforceCacheLimit()
        viewModelScope.launch {
            if (settingsRepository.autoUpdateEnabled.first()) {
                checkForUpdates()
            }
        }
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

    fun setDarkMode(mode: DarkMode) {
        viewModelScope.launch {
            settingsRepository.setDarkMode(mode)
        }
    }

    fun setPaletteStyle(style: PaletteStyle) {
        viewModelScope.launch {
            settingsRepository.setPaletteStyle(style)
        }
    }

    fun setPureBlack(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPureBlack(enabled)
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



    fun setNormalizeVolume(enabled: Boolean) {
        audioEngine.setSoundCheck(enabled)
        viewModelScope.launch {
            settingsRepository.setNormalizeVolume(enabled)
        }
    }

    fun setBassBoostEnabled(enabled: Boolean) {
        audioEngine.setSimpleBass(enabled)
    }

    fun setSongBassEnabled(enabled: Boolean) {
        audioEngine.setSimpleBass(enabled)
    }

    fun setSongBassStrength(percent: Int) {
        audioEngine.setSimpleBassStrength(percent)
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
            enforceCacheLimit(limitMb = mb)
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

    fun setAutoUpdateEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoUpdateEnabled(enabled)
        }
        if (enabled) {
            checkForUpdates()
        }
    }

    fun checkForUpdates() {
        if (_appUpdateState.value.isChecking || _appUpdateState.value.isDownloading) return

        viewModelScope.launch {
            _appUpdateState.value = AppUpdateState(
                isChecking = true,
                statusText = "Checking for updates..."
            )

            appUpdateService.checkForUpdates(appVersionName).fold(
                onSuccess = { result ->
                    _appUpdateState.value = if (result.isUpdateAvailable) {
                        if (!result.downloadUrl.isNullOrBlank()) {
                            AppUpdateState(
                                isChecking = false,
                                statusText = "New version ${result.latestVersion} is available",
                                isUpdateAvailable = true,
                                latestVersion = result.latestVersion,
                                downloadUrl = result.downloadUrl
                            )
                        } else {
                            AppUpdateState(
                                isChecking = false,
                                statusText = "New version ${result.latestVersion} found, but in-app package is unavailable",
                                isUpdateAvailable = true,
                                latestVersion = result.latestVersion
                            )
                        }
                    } else {
                        AppUpdateState(
                            isChecking = false,
                            statusText = "You are on the latest version (${result.currentVersion})",
                            isUpdateAvailable = false,
                            latestVersion = result.latestVersion
                        )
                    }
                },
                onFailure = {
                    _appUpdateState.value = AppUpdateState(
                        isChecking = false,
                        statusText = "Failed to check updates. Try again."
                    )
                }
            )
        }
    }

    fun downloadUpdate() {
        val currentState = _appUpdateState.value
        if (currentState.isChecking || currentState.isDownloading) return

        val downloadUrl = currentState.downloadUrl
        val latestVersion = currentState.latestVersion
        if (downloadUrl.isNullOrBlank() || latestVersion.isNullOrBlank()) {
            _appUpdateState.value = currentState.copy(
                statusText = "No in-app update package is available for this release"
            )
            return
        }

        viewModelScope.launch {
            _appUpdateState.value = currentState.copy(
                isChecking = false,
                isDownloading = true,
                statusText = "Downloading v$latestVersion..."
            )

            runCatching {
                val downloader = UpdateDownloader(context)
                downloader.downloadApk(downloadUrl, "SonicMusic-$latestVersion.apk")
            }.onSuccess {
                _appUpdateState.value = AppUpdateState(
                    isChecking = false,
                    isDownloading = false,
                    statusText = "Downloading v$latestVersion in background",
                    isUpdateAvailable = true,
                    latestVersion = latestVersion,
                    downloadUrl = downloadUrl
                )
            }.onFailure { error ->
                Log.e("SettingsVM", "Failed to start in-app update download", error)
                _appUpdateState.value = AppUpdateState(
                    isChecking = false,
                    isDownloading = false,
                    statusText = "Failed to start update download. Try again.",
                    isUpdateAvailable = true,
                    latestVersion = latestVersion,
                    downloadUrl = downloadUrl
                )
            }
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

    /**
     * Enforce the cache size limit by deleting oldest files first.
     * Walks both internal and external cache directories.
     */
    private fun enforceCacheLimit(limitMb: Long? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val maxBytes = (limitMb ?: settingsRepository.cacheSizeLimit.first()) * 1024L * 1024L
                    if (maxBytes <= 0) return@withContext

                    // Collect all cached files across both cache dirs
                    val cacheDirs = listOfNotNull(context.cacheDir, context.externalCacheDir)
                    val allFiles = cacheDirs.flatMap { dir ->
                        dir.walkTopDown()
                            .filter { it.isFile }
                            .toList()
                    }

                    var totalSize = allFiles.sumOf { it.length() }
                    if (totalSize <= maxBytes) return@withContext

                    // Sort by last modified (oldest first) and delete until under limit
                    val sorted = allFiles.sortedBy { it.lastModified() }
                    var deleted = 0
                    for (file in sorted) {
                        if (totalSize <= maxBytes) break
                        val fileSize = file.length()
                        if (file.delete()) {
                            totalSize -= fileSize
                            deleted++
                        }
                    }

                    if (deleted > 0) {
                        Log.d("SettingsVM", "🗑️ Evicted $deleted cache files to enforce ${limitMb ?: "saved"}MB limit")
                    }
                } catch (e: Exception) {
                    Log.e("SettingsVM", "Cache enforcement failed", e)
                }
            }
            // Refresh displayed size after enforcement
            calculateCacheSize()
        }
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
