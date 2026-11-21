package com.sonicmusic.app.features.settings.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.sonicmusic.app.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonicmusic.app.core.cache.DiskCacheMaintenance
import com.sonicmusic.app.core.updater.UpdateDownloader
import com.sonicmusic.app.core.updater.UpdatePackage
import com.sonicmusic.app.features.updates.data.AppUpdateService
import com.sonicmusic.app.features.updates.receiver.UpdateNotifications
import com.sonicmusic.app.data.repository.SettingsRepository
import com.sonicmusic.app.domain.model.FullPlayerStyle
import com.sonicmusic.app.domain.model.ThemeMode
import com.sonicmusic.app.domain.model.ThumbnailShape
import com.sonicmusic.app.domain.model.PlayerControlStyle
import com.sonicmusic.app.domain.model.NavbarStyle
import com.sonicmusic.app.domain.repository.RecentSearchRepository
import com.sonicmusic.app.features.player.audio.AudioEngine
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import android.annotation.SuppressLint
import com.sonicmusic.app.domain.model.DarkMode
import com.sonicmusic.app.domain.model.PaletteStyle
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val regionRepository: com.sonicmusic.app.data.repository.RegionRepository,
    private val audioEngine: AudioEngine,
    private val appUpdateService: AppUpdateService,
    private val recentSearchRepository: RecentSearchRepository,
    private val audioCapabilityDetector: com.sonicmusic.app.features.player.audio.AudioCapabilityDetector,
    private val diskCache: DiskCacheMaintenance,
    @SuppressLint("StaticFieldLeak") @ApplicationContext private val context: Context
) : ViewModel() {

    data class AppUpdateState(
        val isChecking: Boolean = false,
        val isDownloading: Boolean = false,
        val downloadProgress: Int = 0,
        val statusText: String = "Check for the latest app release",
        val isUpdateAvailable: Boolean = false,
        val latestVersion: String? = null,
        val updatePackage: UpdatePackage? = null
    ) {
        val downloadUrl: String? get() = updatePackage?.downloadUrl
    }

    private val whileObserved = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000)

    // Audio Engine Settings
    val normalizeVolume = audioEngine.audioEngineState
        .map { it.soundCheckEnabled }
        .stateIn(viewModelScope, whileObserved, audioEngine.audioEngineState.value.soundCheckEnabled)

    val balancedEqEnabled = audioEngine.audioEngineState
        .map { it.eqEnabled && it.eqPreset == AudioEngine.BALANCED_EQ }
        .stateIn(viewModelScope, whileObserved, false)

    val equalizerAvailable = audioEngine.eqBands
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, whileObserved, audioEngine.eqBands.value.isNotEmpty())

    fun setBalancedEq(enabled: Boolean) = audioEngine.setBalancedEq(enabled)

    val crossfadeDuration = audioEngine.audioEngineState
        .map { state ->
            if (state.crossfadeEnabled) {
                (state.crossfadeDuration / 1000).coerceIn(0, 12)
            } else {
                0
            }
        }
        .stateIn(viewModelScope, whileObserved, 0)

    val spatialAudioMode = audioEngine.spatialAudioMode

    val spatialAudioEnabled: StateFlow<Boolean> = audioEngine.audioEngineState
        .map { it.spatialAudioEnabled }
        .stateIn(viewModelScope, whileObserved, true)

    val audioCapability: StateFlow<com.sonicmusic.app.features.player.audio.AudioCapabilityDetector.AudioCapability> =
        audioCapabilityDetector.capability
            .stateIn(
                viewModelScope,
                whileObserved,
                com.sonicmusic.app.features.player.audio.AudioCapabilityDetector.AudioCapability()
            )

    // Repository Settings
    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode
        .stateIn(viewModelScope, whileObserved, ThemeMode.DYNAMIC)

    val darkMode: StateFlow<DarkMode> = settingsRepository.darkMode
        .stateIn(viewModelScope, whileObserved, DarkMode.SYSTEM)

    val paletteStyle: StateFlow<PaletteStyle> = settingsRepository.paletteStyle
        .stateIn(viewModelScope, whileObserved, PaletteStyle.CONTENT)

    val fullPlayerStyle: StateFlow<FullPlayerStyle> = settingsRepository.fullPlayerStyle
        .stateIn(viewModelScope, whileObserved, FullPlayerStyle.WAVY)

    val dynamicColors: StateFlow<Boolean> = settingsRepository.dynamicColors
        .stateIn(viewModelScope, whileObserved, true)

    val dynamicColorIntensity: StateFlow<Int> = settingsRepository.dynamicColorIntensity
        .stateIn(viewModelScope, whileObserved, 85)

    val gaplessPlayback: StateFlow<Boolean> = settingsRepository.gaplessPlayback
        .stateIn(viewModelScope, whileObserved, true)

    val skipSilence: StateFlow<Boolean> = settingsRepository.skipSilence
        .stateIn(viewModelScope, whileObserved, true)

    val autoQueueSimilar: StateFlow<Boolean> = settingsRepository.autoQueueSimilar
        .stateIn(viewModelScope, whileObserved, false)

    val thumbnailShape: StateFlow<ThumbnailShape> = settingsRepository.thumbnailShape
        .stateIn(viewModelScope, whileObserved, ThumbnailShape.ROUNDED_SQUARE)

    val playerControlStyle: StateFlow<PlayerControlStyle> = settingsRepository.playerControlStyle
        .stateIn(viewModelScope, whileObserved, PlayerControlStyle.MODERN)

    val navbarStyle: StateFlow<NavbarStyle> = settingsRepository.navbarStyle
        .stateIn(viewModelScope, whileObserved, NavbarStyle.MODERN)

    private val _appUpdateState = MutableStateFlow(AppUpdateState())
    val appUpdateState: StateFlow<AppUpdateState> = _appUpdateState.asStateFlow()
        
    // Region Settings
    val countryCode: StateFlow<String?> = regionRepository.countryCode
        .stateIn(viewModelScope, whileObserved, null)

    val countryName: StateFlow<String?> = regionRepository.countryName
        .stateIn(viewModelScope, whileObserved, null)

    val isRegionManual: StateFlow<Boolean> = regionRepository.isRegionManual
        .stateIn(viewModelScope, whileObserved, false)

    private val _cacheSize = MutableStateFlow("Calculating…")
    val cacheSize: StateFlow<String> = _cacheSize.asStateFlow()

    private val appVersionName = BuildConfig.VERSION_NAME
    val appVersion = "$appVersionName (Build ${BuildConfig.VERSION_CODE})"

    init {
        viewModelScope.launch {
            UpdateDownloader(context).observeStatus().collect { message ->
                if (message != null) {
                    _appUpdateState.value = _appUpdateState.value.copy(
                        isDownloading = false,
                        statusText = message
                    )
                }
            }
        }
        viewModelScope.launch {
            if (settingsRepository.autoUpdateEnabled.first()) {
                checkForUpdates()
            }
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

    fun setSpatialAudio(enabled: Boolean, strength: Int = audioEngine.audioEngineState.value.spatialStrength) {
        audioEngine.setSpatialAudio(enabled, strength)
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

    fun setThumbnailShape(shape: ThumbnailShape) {
        viewModelScope.launch {
            settingsRepository.setThumbnailShape(shape)
        }
    }

    fun setPlayerControlStyle(style: PlayerControlStyle) {
        viewModelScope.launch {
            settingsRepository.setPlayerControlStyle(style)
        }
    }

    fun setNavbarStyle(style: NavbarStyle) {
        viewModelScope.launch {
            settingsRepository.setNavbarStyle(style)
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
                                updatePackage = result.updatePackage
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
                onFailure = { error ->
                    _appUpdateState.value = AppUpdateState(
                        isChecking = false,
                        statusText = (error as? IllegalArgumentException)?.message ?: "Failed to check updates. Try again."
                    )
                }
            )
        }
    }

    fun downloadUpdate() {
        val currentState = _appUpdateState.value
        if (currentState.isChecking || currentState.isDownloading) return
        if (UpdateNotifications.openConfirmation(context)) return

        val updatePackage = currentState.updatePackage
        val latestVersion = currentState.latestVersion
        if (updatePackage == null || latestVersion.isNullOrBlank()) {
            _appUpdateState.value = currentState.copy(
                statusText = "No in-app update package is available for this release"
            )
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            _appUpdateState.value = currentState.copy(
                statusText = "Allow SonicMusic to install updates in Android settings, then tap Update again."
            )
            runCatching {
                val permission = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(permission)
            }
            return
        }

        viewModelScope.launch {
            _appUpdateState.value = currentState.copy(
                isChecking = false,
                isDownloading = true,
                downloadProgress = 0,
                statusText = "Downloading v$latestVersion..."
            )

            try {
                val downloader = UpdateDownloader(context)
                val downloadId = withContext(Dispatchers.IO) { downloader.downloadApk(updatePackage) }
                downloader.observeDownloadProgress(downloadId).collect { progress ->
                    _appUpdateState.value = _appUpdateState.value.copy(
                        isDownloading = true,
                        downloadProgress = progress,
                        statusText = "Downloading v$latestVersion… $progress%"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SettingsVM", "Could not download update", e)
                _appUpdateState.value = currentState.copy(
                    isDownloading = false,
                    statusText = (e as? IllegalArgumentException)?.message ?: "Could not download the update. Try again."
                )
            }
        }
    }

    suspend fun clearCache(): Boolean = viewModelScope.async { maintainDiskCache(clear = true) }.await()

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
            maintainDiskCache()
        }
    }

    suspend fun refreshCacheSize() {
        maintainDiskCache()
    }

    private suspend fun maintainDiskCache(clear: Boolean = false): Boolean = try {
        _cacheSize.value = formatFileSize(diskCache.trimAndMeasure(clear))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("SettingsVM", "Could not update cache size", e)
        _cacheSize.value = "Unknown"
        false
    }

    fun refreshRegion() {
        viewModelScope.launch {
            regionRepository.refreshRegion()
        }
    }

    fun setManualCountry(countryCode: String) {
        viewModelScope.launch {
            regionRepository.setManualCountry(countryCode)
        }
    }

    fun setAutoDetectRegion() {
        viewModelScope.launch {
            regionRepository.setAutoDetectRegion()
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
