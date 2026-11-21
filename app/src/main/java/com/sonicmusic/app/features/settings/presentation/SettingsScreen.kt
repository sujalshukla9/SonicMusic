package com.sonicmusic.app.features.settings.presentation

import com.sonicmusic.app.theme.pressScale
import com.sonicmusic.app.theme.pressable
import com.sonicmusic.app.features.player.audio.SpatialAudioMode
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.sonicmusic.app.shared.components.ScreenTitle
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.domain.model.FullPlayerStyle
import com.sonicmusic.app.domain.model.ThemeMode
import com.sonicmusic.app.domain.model.PaletteStyle
import com.sonicmusic.app.domain.model.DarkMode
import com.sonicmusic.app.domain.model.ThumbnailShape
import com.sonicmusic.app.domain.model.PlayerControlStyle
import com.sonicmusic.app.domain.model.NavbarStyle
import com.sonicmusic.app.theme.LocalThumbnailShape
import com.sonicmusic.app.theme.toComposeShape
import com.sonicmusic.app.theme.Dimens
import com.sonicmusic.app.shared.components.AlbumArtThumbnail
import com.sonicmusic.app.features.player.presentation.PlayerViewModel
import com.sonicmusic.app.features.settings.presentation.SettingsViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable

private enum class SettingsSheet {
    None, Theme, DarkMode, ArtworkPalette, SeekBarStyle, AlbumArtShape, RegionSelection
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onNavigateBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val youtubeAccountViewModel: YouTubeAccountViewModel = hiltViewModel()
    val authorizationResolution by youtubeAccountViewModel.resolution.collectAsStateWithLifecycle()
    val youtubeAuthorizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        youtubeAccountViewModel.authorizationResult(result.resultCode, result.data)
    }
    LaunchedEffect(authorizationResolution) {
        val pendingIntent = authorizationResolution ?: return@LaunchedEffect
        youtubeAccountViewModel.resolutionLaunched()
        try {
            youtubeAuthorizationLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
        } catch (e: IllegalStateException) {
            youtubeAccountViewModel.authorizationLaunchFailed()
        }
    }

    // Appearance States
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val darkMode by viewModel.darkMode.collectAsStateWithLifecycle()
    val dynamicColors by viewModel.dynamicColors.collectAsStateWithLifecycle()
    val dynamicColorIntensity by viewModel.dynamicColorIntensity.collectAsStateWithLifecycle()
    val paletteStyle by viewModel.paletteStyle.collectAsStateWithLifecycle()
    val usesArtworkColors = themeMode == ThemeMode.DYNAMIC || themeMode == ThemeMode.PURE_BLACK
    val fullPlayerStyle by viewModel.fullPlayerStyle.collectAsStateWithLifecycle()
    val thumbnailShape by viewModel.thumbnailShape.collectAsStateWithLifecycle()
    val playerControlStyle by viewModel.playerControlStyle.collectAsStateWithLifecycle()
    val navbarStyle by viewModel.navbarStyle.collectAsStateWithLifecycle()
    val currentSong by playerViewModel.currentSong.collectAsStateWithLifecycle()
    
    // Playback States
    val normalizeVolume by viewModel.normalizeVolume.collectAsStateWithLifecycle()
    val balancedEqEnabled by viewModel.balancedEqEnabled.collectAsStateWithLifecycle()
    val equalizerAvailable by viewModel.equalizerAvailable.collectAsStateWithLifecycle()
    val gaplessPlayback by viewModel.gaplessPlayback.collectAsStateWithLifecycle()
    val crossfadeDuration by viewModel.crossfadeDuration.collectAsStateWithLifecycle()
    val skipSilence by viewModel.skipSilence.collectAsStateWithLifecycle()
    val autoQueueSimilar by viewModel.autoQueueSimilar.collectAsStateWithLifecycle()
    val spatialAudioEnabled by viewModel.spatialAudioEnabled.collectAsStateWithLifecycle()
    val spatialAudioMode by viewModel.spatialAudioMode.collectAsStateWithLifecycle()
    val audioCapability by viewModel.audioCapability.collectAsStateWithLifecycle()
    
    // Region States
    val countryName by viewModel.countryName.collectAsStateWithLifecycle()
    val countryCode by viewModel.countryCode.collectAsStateWithLifecycle()
    
    // Update States
    val appUpdateState by viewModel.appUpdateState.collectAsStateWithLifecycle()
    
    var activeSheet by remember { mutableStateOf(SettingsSheet.None) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val colorScheme = MaterialTheme.colorScheme

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        containerColor = colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MediumTopAppBar(
                title = { ScreenTitle("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = colorScheme.surfaceContainer
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = Dimens.maxContentWidth)
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding() + Dimens.screenPadding,
                    bottom = bottomPadding + 32.dp
                ),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
            // ═══════════════════════════════════════════
            // APPEARANCE SECTION
            // ═══════════════════════════════════════════
            item {
                SettingsGroup(
                    title = "Appearance",
                    modifier = Modifier.padding(horizontal = Dimens.screenPadding)
                ) {
                    SettingsActionItem(
                        title = "Select Theme",
                        subtitle = when(themeMode) {
                            ThemeMode.DEFAULT -> "Default"
                            ThemeMode.DYNAMIC -> "Content-based colors"
                            ThemeMode.MATERIAL_YOU -> "Material You"
                            ThemeMode.PURE_BLACK -> "Pure Black"
                        },
                        icon = Icons.Rounded.Palette,
                        onClick = { activeSheet = SettingsSheet.Theme }
                    )
                    SettingsDivider()
                    SettingsActionItem(
                        title = "Dark Mode",
                        subtitle = when(darkMode) {
                            DarkMode.LIGHT -> "Light"
                            DarkMode.DARK -> "Dark"
                            DarkMode.SYSTEM -> "System"
                        },
                        icon = Icons.Rounded.DarkMode,
                        onClick = { activeSheet = SettingsSheet.DarkMode }
                    )
                    if (usesArtworkColors) {
                        SettingsDivider()
                        SettingsSwitchItem(
                            title = "Artwork colors",
                            subtitle = "Use colors from the current song’s cover art",
                            checked = dynamicColors,
                            onCheckedChange = { viewModel.setDynamicColors(it) }
                        )
                        if (dynamicColors) {
                            SettingsDivider()
                            SettingsActionItem(
                                title = "Artwork palette",
                                subtitle = paletteStyle.settingsLabel(),
                                icon = Icons.Rounded.Palette,
                                onClick = { activeSheet = SettingsSheet.ArtworkPalette }
                            )
                            SettingsDivider()
                            SettingsSliderItem(
                                title = "Theme Color Intensity",
                                value = dynamicColorIntensity.toFloat(),
                                onValueChange = { viewModel.setDynamicColorIntensity(it.roundToInt()) },
                                valueRange = 0f..100f,
                                steps = 99,
                                valueText = "$dynamicColorIntensity%"
                            )
                        }
                    }
                    SettingsDivider()
                    SettingsActionItem(
                        title = "Player Style",
                        subtitle = fullPlayerStyleMenuLabel(fullPlayerStyle),
                        icon = Icons.Rounded.MusicNote,
                        onClick = { activeSheet = SettingsSheet.SeekBarStyle }
                    )
                    SettingsDivider()
                    SettingsActionItem(
                        title = "Album Art Shape",
                        subtitle = thumbnailShape.displayName,
                        icon = Icons.Rounded.Interests,
                        onClick = { activeSheet = SettingsSheet.AlbumArtShape }
                    )
                }
            }

            // ═══════════════════════════════════════════
            // PLAYER CONTROLS STYLE SECTION
            // ═══════════════════════════════════════════
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.screenPadding)
                ) {
                    Text(
                        text = "Player Controls Style",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        PlayerControlStyleCard(
                            title = "Classic",
                            style = PlayerControlStyle.CLASSIC,
                            isSelected = playerControlStyle == PlayerControlStyle.CLASSIC,
                            onClick = { viewModel.setPlayerControlStyle(PlayerControlStyle.CLASSIC) },
                            modifier = Modifier.weight(1f)
                        )
                        
                        PlayerControlStyleCard(
                            title = "Modern",
                            style = PlayerControlStyle.MODERN,
                            isSelected = playerControlStyle == PlayerControlStyle.MODERN,
                            onClick = { viewModel.setPlayerControlStyle(PlayerControlStyle.MODERN) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Navigation Bar Style",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        NavbarStyleCard(
                            title = "Modern",
                            subtitle = "Floating bar with circular player",
                            style = NavbarStyle.MODERN,
                            isSelected = navbarStyle == NavbarStyle.MODERN,
                            onClick = { viewModel.setNavbarStyle(NavbarStyle.MODERN) },
                            modifier = Modifier.weight(1f)
                        )

                        NavbarStyleCard(
                            title = "Classical",
                            subtitle = "Docked bar with mini player strip",
                            style = NavbarStyle.CLASSICAL,
                            isSelected = navbarStyle == NavbarStyle.CLASSICAL,
                            onClick = { viewModel.setNavbarStyle(NavbarStyle.CLASSICAL) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ═══════════════════════════════════════════
            // PLAYBACK & AUDIO SECTION
            // ═══════════════════════════════════════════
            item {
                SettingsGroup(
                    title = "Playback",
                    modifier = Modifier.padding(horizontal = Dimens.screenPadding)
                ) {
                    SettingsSwitchItem(
                        title = "Normalize Volume",
                        subtitle = "Use streaming loudness data or ReplayGain tags for more consistent volume",
                        checked = normalizeVolume,
                        onCheckedChange = { viewModel.setNormalizeVolume(it) }
                    )
                    SettingsDivider()
                    SettingsSwitchItem(
                        title = "Balanced EQ",
                        subtitle = if (equalizerAvailable) "Gentle bass and vocal presence with clipping headroom"
                            else "Device equalizer is unavailable",
                        checked = balancedEqEnabled,
                        enabled = equalizerAvailable,
                        onCheckedChange = viewModel::setBalancedEq
                    )
                    SettingsDivider()
                    SettingsSwitchItem(
                        title = "Gapless Playback",
                        subtitle = "Pre-buffer next track for seamless audio transition",
                        checked = gaplessPlayback,
                        onCheckedChange = { viewModel.setGaplessPlayback(it) }
                    )
                    SettingsDivider()
                    SettingsSwitchItem(
                        title = "Audio Crossfade",
                        subtitle = if (crossfadeDuration > 0) "Seamless overlapping playback (${crossfadeDuration}s)" else "Overlap playback between consecutive songs",
                        icon = Icons.Rounded.GraphicEq,
                        checked = crossfadeDuration > 0,
                        onCheckedChange = { enabled ->
                            viewModel.setCrossfadeDuration(if (enabled) 5 else 0)
                        }
                    )
                    if (crossfadeDuration > 0) {
                        SettingsSliderItem(
                            title = "Crossfade Duration",
                            value = crossfadeDuration.toFloat(),
                            onValueChange = { viewModel.setCrossfadeDuration(it.toInt().coerceIn(1, 12)) },
                            valueRange = 1f..12f,
                            steps = 10,
                            valueText = "${crossfadeDuration}s"
                        )
                    }
                    SettingsDivider()
                    SettingsSwitchItem(
                        title = "Skip Silence",
                        subtitle = "Automatically skip silent frames within tracks",
                        checked = skipSilence,
                        onCheckedChange = { viewModel.setSkipSilence(it) }
                    )
                    SettingsDivider()
                    val spatialSubtitle = when (spatialAudioMode) {
                        SpatialAudioMode.OFF -> null
                        SpatialAudioMode.WAITING -> "Available when a supported track starts"
                        SpatialAudioMode.SYSTEM -> "System Spatializer on ${audioCapability.currentOutputRouteName}"
                        SpatialAudioMode.VIRTUALIZER -> "Device virtualization on ${audioCapability.currentOutputRouteName}"
                        SpatialAudioMode.UNAVAILABLE -> "Unavailable for the current track or output"
                    }
                    SettingsSwitchItem(
                        title = "Spatial Audio",
                        subtitle = spatialSubtitle,
                        icon = Icons.Rounded.SurroundSound,
                        checked = spatialAudioEnabled,
                        onCheckedChange = { viewModel.setSpatialAudio(it) }
                    )
                    SettingsDivider()
                    SettingsActionItem(
                        title = "Device Equalizer",
                        subtitle = "Open system audio effects panel",
                        icon = Icons.Rounded.GraphicEq,
                        onClick = {
                            if (!openDeviceEqualizerPanel(context)) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("No device equalizer found")
                                }
                            }
                        }
                    )
                    SettingsDivider()
                    SettingsSwitchItem(
                        title = "Auto Queue Similar",
                        subtitle = "Continuously add similar songs when queue ends",
                        checked = autoQueueSimilar,
                        onCheckedChange = { viewModel.setAutoQueueSimilar(it) }
                    )
                }
            }

            // ═══════════════════════════════════════════
            // STORAGE & DOWNLOADS SECTION
            // ═══════════════════════════════════════════
            item {
                val cacheSize by viewModel.cacheSize.collectAsStateWithLifecycle()
                LaunchedEffect(viewModel) { viewModel.refreshCacheSize() }
                SettingsGroup(
                    title = "Storage & Downloads",
                    modifier = Modifier.padding(horizontal = Dimens.screenPadding)
                ) {
                    SettingsActionItem(
                        title = "Clear Cache",
                        subtitle = "Cached songs size: $cacheSize",
                        icon = Icons.Rounded.DeleteForever,
                        onClick = {
                            scope.launch {
                                val message = if (viewModel.clearCache()) {
                                    "Cache cleared"
                                } else {
                                    "Couldn't clear the cache"
                                }
                                snackbarHostState.showSnackbar(message)
                            }
                        }
                    )
                    SettingsDivider()
                    SettingsActionItem(
                        title = "Clear Search History",
                        subtitle = "Erase your past search queries from local database",
                        icon = Icons.Rounded.History,
                        onClick = {
                            viewModel.clearSearchHistory()
                            scope.launch {
                                snackbarHostState.showSnackbar("Search history cleared")
                            }
                        }
                    )
                }
            }

            // ═══════════════════════════════════════════
            // REGIONAL SECTION
            // ═══════════════════════════════════════════
            item {
                SettingsGroup(
                    title = "Content Regional Settings",
                    modifier = Modifier.padding(horizontal = Dimens.screenPadding)
                ) {
                    val isRegionManual by viewModel.isRegionManual.collectAsStateWithLifecycle()
                    val regionSubtitle = when {
                        countryName != null && countryCode != null -> {
                            val flag = com.sonicmusic.app.domain.model.CountryUtils.countryCodeToEmoji(countryCode ?: "")
                            val modeLabel = if (isRegionManual) "Manual" else "Auto-detected"
                            "$flag $countryName ($countryCode) • $modeLabel"
                        }
                        countryCode != null -> {
                            val flag = com.sonicmusic.app.domain.model.CountryUtils.countryCodeToEmoji(countryCode ?: "")
                            val modeLabel = if (isRegionManual) "Manual" else "Auto-detected"
                            "$flag $countryCode • $modeLabel"
                        }
                        else -> "Detecting region..."
                    }
                    SettingsActionItem(
                        title = "Content Country",
                        subtitle = regionSubtitle,
                        icon = Icons.Rounded.Public,
                        onClick = {
                            activeSheet = SettingsSheet.RegionSelection
                        }
                    )
                }
            }

            // ═══════════════════════════════════════════
            // ABOUT SECTION
            // ═══════════════════════════════════════════
            item {
                SettingsGroup(
                    title = "About",
                    modifier = Modifier.padding(horizontal = Dimens.screenPadding)
                ) {
                    ListItem(
                        headlineContent = { Text("App Version", fontWeight = FontWeight.Medium) },
                        supportingContent = { Text(viewModel.appVersion) },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = colorScheme.onSurfaceVariant
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    SettingsDivider()
                    SettingsActionItem(
                        title = "Check for Updates",
                        subtitle = appUpdateState.statusText,
                        icon = Icons.Rounded.Refresh,
                        onClick = {
                            if (appUpdateState.isUpdateAvailable && !appUpdateState.downloadUrl.isNullOrBlank()) {
                                viewModel.downloadUpdate()
                            } else {
                                viewModel.checkForUpdates()
                            }
                        },
                        trailingContent = {
                            if (appUpdateState.isChecking || appUpdateState.isDownloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                    contentDescription = null,
                                    tint = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                }
            }
            
            item(key = "youtube-account-sync") {
                YouTubeAccountSettings(youtubeAccountViewModel)
            }

            item(key = "youtube-premium-playback") {
                YouTubePlaybackSettings()
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

    // Modal Bottom Sheets for choices
    if (activeSheet != SettingsSheet.None) {
        ModalBottomSheet(
            onDismissRequest = { activeSheet = SettingsSheet.None },
            containerColor = colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp
        ) {
            var previewShape by remember(thumbnailShape) { mutableStateOf(thumbnailShape) }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = when (activeSheet) {
                        SettingsSheet.Theme -> "Select Theme"
                        SettingsSheet.DarkMode -> "Dark Mode"
                        SettingsSheet.ArtworkPalette -> "Artwork palette"
                        SettingsSheet.SeekBarStyle -> "Seek Bar Style"
                        SettingsSheet.AlbumArtShape -> "Album Art Shape"
                        SettingsSheet.RegionSelection -> "Content Region"
                        else -> ""
                    },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )
                
                when (activeSheet) {
                    SettingsSheet.Theme -> {
                        ThemeMode.entries.forEach { mode ->
                            OptionItem(
                                text = when(mode) {
                                    ThemeMode.DEFAULT -> "Default"
                                    ThemeMode.DYNAMIC -> "Content-based colors"
                                    ThemeMode.MATERIAL_YOU -> "Material You"
                                    ThemeMode.PURE_BLACK -> "Pure Black"
                                },
                                selected = themeMode == mode,
                                onClick = {
                                    viewModel.setThemeMode(mode)
                                    activeSheet = SettingsSheet.None
                                }
                            )
                        }
                    }
                    SettingsSheet.DarkMode -> {
                        DarkMode.entries.forEach { mode ->
                            OptionItem(
                                text = when(mode) {
                                    DarkMode.LIGHT -> "Light"
                                    DarkMode.DARK -> "Dark"
                                    DarkMode.SYSTEM -> "System"
                                },
                                selected = darkMode == mode,
                                onClick = {
                                    viewModel.setDarkMode(mode)
                                    activeSheet = SettingsSheet.None
                                }
                            )
                        }
                    }
                    SettingsSheet.ArtworkPalette -> {
                        PaletteStyle.entries.forEach { style ->
                            OptionItem(
                                text = style.settingsLabel(),
                                selected = paletteStyle == style,
                                onClick = {
                                    viewModel.setPaletteStyle(style)
                                    activeSheet = SettingsSheet.None
                                }
                            )
                        }
                    }
                    SettingsSheet.SeekBarStyle -> {
                        FullPlayerStyle.entries.forEach { style ->
                            OptionItem(
                                text = fullPlayerStyleMenuLabel(style),
                                selected = fullPlayerStyle == style,
                                onClick = {
                                    viewModel.setFullPlayerStyle(style)
                                    activeSheet = SettingsSheet.None
                                }
                            )
                        }
                    }
                    SettingsSheet.AlbumArtShape -> {
                        // 1. Live Preview of the shape on actual song or fallback gradient
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (currentSong?.thumbnailUrl != null) {
                                AlbumArtThumbnail(
                                    imageUrl = currentSong?.thumbnailUrl,
                                    shape = previewShape,
                                    modifier = Modifier.size(160.dp),
                                    shadowElevation = 8.dp,
                                    highQuality = true
                                )
                            } else {
                                val previewShadowShape = when (previewShape) {
                                    ThumbnailShape.CIRCLE -> androidx.compose.foundation.shape.CircleShape
                                    ThumbnailShape.SQUARE -> androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
                                    ThumbnailShape.ROUNDED_SQUARE -> androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                                    ThumbnailShape.ARCH -> com.sonicmusic.app.theme.ArchShape
                                    ThumbnailShape.PILL -> com.sonicmusic.app.theme.PillShape
                                    else -> androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(160.dp)
                                        .shadow(8.dp, previewShadowShape)
                                        .clip(previewShape.toComposeShape())
                                        .background(
                                            Brush.linearGradient(
                                                colors = listOf(
                                                    MaterialTheme.colorScheme.primary,
                                                    MaterialTheme.colorScheme.tertiary
                                                )
                                            )
                                        )
                                )
                            }
                        }

                        // 2. Selectable list
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(ThumbnailShape.entries.toTypedArray()) { shapeOption ->
                                val isSelected = previewShape == shapeOption
                                val borderModifier = if (isSelected) {
                                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shapeOption.toComposeShape())
                                } else Modifier
                                
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clickable { previewShape = shapeOption }
                                        .padding(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .then(borderModifier)
                                            .padding(if (isSelected) 3.dp else 0.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (currentSong?.thumbnailUrl != null) {
                                            AlbumArtThumbnail(
                                                imageUrl = currentSong?.thumbnailUrl,
                                                shape = shapeOption,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(shapeOption.toComposeShape())
                                                    .background(
                                                        Brush.linearGradient(
                                                            colors = listOf(
                                                                MaterialTheme.colorScheme.primary,
                                                                MaterialTheme.colorScheme.tertiary
                                                            )
                                                        )
                                                    )
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = shapeOption.displayName,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // 3. Confirm and Cancel Buttons
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            OutlinedButton(
                                onClick = { activeSheet = SettingsSheet.None },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = {
                                    viewModel.setThumbnailShape(previewShape)
                                    activeSheet = SettingsSheet.None
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Apply")
                            }
                        }
                    }
                    SettingsSheet.RegionSelection -> {
                        var searchQuery by remember { mutableStateOf("") }
                        val allCountries = remember { com.sonicmusic.app.domain.model.CountryUtils.getAllCountries() }
                        val filteredCountries = remember(searchQuery) {
                            if (searchQuery.isBlank()) {
                                allCountries
                            } else {
                                val q = searchQuery.trim().lowercase()
                                allCountries.filter {
                                    it.name.lowercase().contains(q) || it.code.lowercase().contains(q)
                                }
                            }
                        }
                        val isRegionManual by viewModel.isRegionManual.collectAsStateWithLifecycle()

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                placeholder = { Text("Search countries...") },
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Rounded.Close, contentDescription = "Clear")
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                            ) {
                                item {
                                    OptionItem(
                                        text = "🌐  Auto-detect (Network & Session)",
                                        selected = !isRegionManual,
                                        onClick = {
                                            viewModel.setAutoDetectRegion()
                                            activeSheet = SettingsSheet.None
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Region set to Auto-detect")
                                            }
                                        }
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                }

                                items(filteredCountries, key = { it.code }) { country ->
                                    val isSelected = isRegionManual && countryCode.equals(country.code, ignoreCase = true)
                                    OptionItem(
                                        text = "${country.flagEmoji}  ${country.name} (${country.code})",
                                        selected = isSelected,
                                        onClick = {
                                            viewModel.setManualCountry(country.code)
                                            activeSheet = SettingsSheet.None
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Region set to ${country.name}")
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

private fun PaletteStyle.settingsLabel(): String = when (this) {
    PaletteStyle.CONTENT -> "Material 3 Content"
    PaletteStyle.TONAL_SPOT -> "Tonal Spot"
    PaletteStyle.VIBRANT -> "Vibrant"
    PaletteStyle.EXPRESSIVE -> "Expressive"
    PaletteStyle.FIDELITY -> "Fidelity"
}

@Composable
private fun YouTubeAccountSettings(viewModel: YouTubeAccountViewModel) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val operation by viewModel.operation.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isBusy = operation != YouTubeAccountViewModel.Operation.Idle
    var showDisconnect by rememberSaveable { mutableStateOf(false) }
    val status = when (operation) {
        YouTubeAccountViewModel.Operation.Syncing -> "Connecting to YouTube and syncing music…"
        YouTubeAccountViewModel.Operation.Disconnecting -> "Disconnecting…"
        YouTubeAccountViewModel.Operation.Idle -> message ?: connection?.let {
            val lastSync = DateUtils.getRelativeTimeSpanString(it.lastSyncedAt)
            "Last synced $lastSync"
        } ?: "Copy playlists you created and liked music into SonicMusic."
    }

    SettingsGroup(
        title = "YouTube Music account sync",
        modifier = Modifier.padding(horizontal = Dimens.screenPadding)
    ) {
        SettingsActionItem(
            title = if (connection == null) "Connect Google Account" else "Sync now",
            subtitle = status,
            icon = Icons.Rounded.Sync,
            enabled = !isBusy,
            onClick = viewModel::sync,
            trailingContent = if (isBusy) {
                { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
            } else null
        )
        if (connection != null) {
            SettingsDivider()
            SettingsActionItem(
                title = "Disconnect",
                icon = Icons.Rounded.LinkOff,
                enabled = !isBusy,
                onClick = { showDisconnect = true }
            )
        }
    }

    if (showDisconnect) {
        AlertDialog(
            onDismissRequest = { showDisconnect = false },
            title = { Text("Disconnect YouTube Music?") },
            text = { Text("This removes the synced playlists from SonicMusic.") },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnect = false
                    viewModel.disconnect()
                }) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnect = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun YouTubePlaybackSettings(viewModel: YouTubePlaybackViewModel = hiltViewModel()) {
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var showImport by remember { mutableStateOf(false) }

    SettingsGroup(
        title = "YouTube Music Premium playback",
        modifier = Modifier.padding(horizontal = Dimens.screenPadding)
    ) {
        SettingsActionItem(
            title = if (connected) "Replace playback session" else "Connect playback session",
            subtitle = message ?: if (busy) "Checking playback session…"
                else if (connected) "Session saved for personalized Home and high-quality streaming"
                else "Connect your YouTube Music account for personalized Home and high-quality streaming.",
            icon = Icons.Rounded.Headphones,
            enabled = !busy,
            onClick = { showImport = true },
            trailingContent = if (busy) {
                { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
            } else null
        )
        if (connected) {
            SettingsDivider()
            SettingsActionItem(
                title = "Disconnect playback session",
                icon = Icons.Rounded.LinkOff,
                enabled = !busy,
                onClick = viewModel::disconnect,
            )
        }
    }

    if (showImport) {
        var cookie by remember { mutableStateOf("") }
        var index by remember { mutableStateOf("0") }
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("Connect playback session") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Paste the Cookie header from your own signed-in YouTube Music session. SonicMusic encrypts it on this device.")
                    OutlinedTextField(
                        value = cookie,
                        onValueChange = { if (it.length <= 16_384) cookie = it },
                        label = { Text("YouTube Music Cookie header") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = index,
                        onValueChange = { if (it.length <= 9 && it.all(Char::isDigit)) index = it },
                        label = { Text("Google account index") },
                        supportingText = { Text("Use 0 for the first account.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = cookie.isNotBlank() && index.isNotBlank(),
                    onClick = {
                        viewModel.connect(cookie, index)
                        cookie = ""
                        showImport = false
                    },
                ) { Text("Connect") }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false }) { Text("Cancel") }
            },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)
        )
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val presses = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    ListItem(
        leadingContent = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = null,
                thumbContent = if (checked) {
                    {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else null
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.pressScale(presses, targetScale = 0.985f, enabled = enabled).toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            interactionSource = presses,
            indication = androidx.compose.material3.ripple(),
            onValueChange = onCheckedChange
        )
    )
}

@Composable
private fun SettingsActionItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    trailingContent: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = trailingContent ?: {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.pressable(enabled = enabled, onClick = onClick)
    )
}

@Composable
private fun SettingsSliderItem(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    valueText: String = "",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (valueText.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = valueText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun OptionItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(text, fontWeight = FontWeight.Medium) },
        trailingContent = {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            headlineColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
        ),
        modifier = Modifier.pressable(onClick = onClick)
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

private fun fullPlayerStyleMenuLabel(style: FullPlayerStyle): String {
    return when (style) {
        FullPlayerStyle.NORMAL -> "Normal"
        FullPlayerStyle.WAVY -> "Wavy"
    }
}

private fun openDeviceEqualizerPanel(context: Context): Boolean {
    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
    return runCatching { context.startActivity(intent) }.isSuccess
}

@Composable
private fun PlayerControlStyleCard(
    title: String,
    style: PlayerControlStyle,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val borderStroke = if (isSelected) {
        androidx.compose.foundation.BorderStroke(2.dp, colorScheme.primary)
    } else {
        androidx.compose.foundation.BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
    
    val containerColor = if (isSelected) {
        colorScheme.primaryContainer.copy(alpha = 0.15f)
    } else {
        colorScheme.surfaceContainerHigh
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .height(130.dp)
            .shadow(elevation = if (isSelected) 4.dp else 0.dp, shape = RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        border = borderStroke
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) colorScheme.primary else colorScheme.onSurface
                )
            }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (style == PlayerControlStyle.CLASSIC) {
                    val accent = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant
                    Column(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(colorScheme.outlineVariant)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.4f)
                                    .fillMaxHeight()
                                    .background(accent)
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(colorScheme.onSurfaceVariant))
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 4.dp, bottomEnd = 4.dp))
                                    .background(colorScheme.secondaryContainer)
                            )
                            Box(
                                modifier = Modifier
                                    .width(36.dp)
                                    .height(20.dp)
                                    .clip(CircleShape)
                                    .background(accent)
                            )
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 8.dp, bottomEnd = 8.dp))
                                    .background(colorScheme.secondaryContainer)
                            )
                            Box(Modifier.size(6.dp).clip(CircleShape).background(colorScheme.onSurfaceVariant))
                        }
                    }
                } else {
                    val accent = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant
                    Column(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                Modifier.weight(1f).height(18.dp).clip(CircleShape).background(accent)
                            )
                            Box(
                                Modifier.size(18.dp).clip(RoundedCornerShape(6.dp))
                                    .background(colorScheme.secondaryContainer)
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(18.dp).clip(RoundedCornerShape(6.dp))
                                    .background(colorScheme.secondaryContainer)
                            )
                            Box(
                                Modifier.weight(1f).height(3.dp).clip(CircleShape)
                                    .background(colorScheme.outlineVariant)
                            ) {
                                Box(
                                    Modifier.fillMaxWidth(0.4f).fillMaxHeight().background(accent)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavbarStyleCard(
    title: String,
    subtitle: String,
    style: NavbarStyle,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val borderStroke = if (isSelected) {
        androidx.compose.foundation.BorderStroke(2.dp, colorScheme.primary)
    } else {
        androidx.compose.foundation.BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.5f))
    }

    val containerColor = if (isSelected) {
        colorScheme.primaryContainer.copy(alpha = 0.15f)
    } else {
        colorScheme.surfaceContainerHigh
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .height(140.dp)
            .shadow(elevation = if (isSelected) 4.dp else 0.dp, shape = RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        border = borderStroke
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) colorScheme.primary else colorScheme.onSurface
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                if (style == NavbarStyle.MODERN) {
                    val accent = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(18.dp)
                                .clip(CircleShape)
                                .background(colorScheme.surfaceContainer)
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.width(16.dp).height(8.dp).clip(CircleShape).background(accent))
                            repeat(3) {
                                Box(Modifier.size(5.dp).clip(CircleShape).background(accent.copy(alpha = 0.5f)))
                            }
                        }
                        Box(
                            modifier = Modifier.size(18.dp).clip(CircleShape).background(accent),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(Modifier.size(13.dp).clip(CircleShape).background(colorScheme.secondaryContainer))
                        }
                    }
                } else {
                    // Visual preview of Classical edge-to-edge docked navbar with mini player strip
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // MiniPlayer horizontal strip above
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.95f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.6f) else colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(4.dp)
                                        .clip(CircleShape)
                                        .background(colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                )
                            }
                        }

                        // Docked rectangular bottom bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(18.dp)
                                .background(if (isSelected) colorScheme.secondaryContainer.copy(alpha = 0.7f) else colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)
                            ) {
                                repeat(4) { idx ->
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(if (idx == 0 && isSelected) colorScheme.primary else colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}
