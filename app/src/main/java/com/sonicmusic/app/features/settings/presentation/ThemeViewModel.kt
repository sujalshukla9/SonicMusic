package com.sonicmusic.app.features.settings.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.ImageRequest
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.domain.model.DarkMode
import com.sonicmusic.app.domain.model.PaletteStyle
import com.sonicmusic.app.domain.model.ThemeMode
import com.sonicmusic.app.domain.model.ThumbnailShape
import com.sonicmusic.app.features.player.service.PlayerServiceConnection
import com.sonicmusic.app.theme.ArtworkColors
import com.sonicmusic.app.theme.DynamicThemeExtractor
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

import kotlinx.coroutines.withContext

@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val playerServiceConnection: PlayerServiceConnection,
    @SuppressLint("StaticFieldLeak") @ApplicationContext private val context: Context
) : ViewModel() {

    // Use the Coil singleton configured in SonicMusicApplication.newImageLoader()
    // so we benefit from the tuned memory/disk caches and respectCacheHeaders(false).
    private val imageLoader get() = context.imageLoader

    // User Preferences
    val themeMode: StateFlow<ThemeMode> = settingsDataStore.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.DYNAMIC)

    val darkMode: StateFlow<DarkMode> = settingsDataStore.darkMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, DarkMode.SYSTEM)

    val paletteStyle: StateFlow<PaletteStyle> = settingsDataStore.paletteStyle
        .stateIn(viewModelScope, SharingStarted.Eagerly, PaletteStyle.CONTENT)

    val pureBlack: StateFlow<Boolean> = settingsDataStore.pureBlack
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val dynamicColorsEnabled: StateFlow<Boolean> = settingsDataStore.dynamicColors
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val dynamicColorIntensity: StateFlow<Int> = settingsDataStore.dynamicColorIntensity
        .stateIn(viewModelScope, SharingStarted.Eagerly, 85)

    val thumbnailShape: StateFlow<ThumbnailShape> = settingsDataStore.thumbnailShape
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThumbnailShape.ROUNDED_SQUARE)

    private val _artworkColors = MutableStateFlow<ArtworkColors?>(null)
    val artworkColors: StateFlow<ArtworkColors?> = _artworkColors.asStateFlow()

    private val seedCache = LruCache<String, ArtworkColors>(60)
    private var currentArtwork: String? = null
    private var colorExtractionJob: Job? = null

    init {
        viewModelScope.launch {
            val lastColors = settingsDataStore.lastArtworkColors.first()
            if (_artworkColors.value == null) _artworkColors.value = lastColors
        }

        viewModelScope.launch {
            combine(playerServiceConnection.currentSong, themeMode, dynamicColorsEnabled, dynamicColorIntensity) { song, mode, enabled, intensity ->
                song?.thumbnailUrl?.takeIf {
                    it.isNotBlank() && enabled && intensity > 0 &&
                        (mode == ThemeMode.DYNAMIC || mode == ThemeMode.PURE_BLACK)
                }
            }.distinctUntilChanged().collect(::onAlbumArtChanged)
        }
    }

    private fun onAlbumArtChanged(uri: String?) {
        currentArtwork = uri
        colorExtractionJob?.cancel()
        if (uri == null) return // Keep the last valid seed while artwork is unavailable.

        colorExtractionJob = viewModelScope.launch {
            try {
                val colors = seedCache.get(uri) ?: run {
                    val bitmap = withContext(Dispatchers.IO) {
                        val request = ImageRequest.Builder(context)
                            .data(uri)
                            .size(128)
                            .allowHardware(false)
                            // Keep the small software decode separate from display artwork.
                            .memoryCacheKey("theme-seed:$uri")
                            .build()
                        val result = imageLoader.execute(request) as? coil.request.SuccessResult
                        val drawable = result?.drawable
                        (drawable as? BitmapDrawable)?.bitmap ?: drawable?.toBitmap(128, 128)
                    } ?: return@launch
                    DynamicThemeExtractor.extractColors(bitmap).also { seedCache.put(uri, it) }
                }
                coroutineContext.ensureActive()
                if (currentArtwork != uri) return@launch

                _artworkColors.value = colors
                settingsDataStore.setLastArtworkColors(colors)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ThemeVM", "Color extraction failed for $uri", e)
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsDataStore.setThemeMode(mode) }
    }

    fun setDarkMode(mode: DarkMode) {
        viewModelScope.launch { settingsDataStore.setDarkMode(mode) }
    }

    fun setPaletteStyle(style: PaletteStyle) {
        viewModelScope.launch { settingsDataStore.setPaletteStyle(style) }
    }

    fun setPureBlack(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setPureBlack(enabled) }
    }
}
