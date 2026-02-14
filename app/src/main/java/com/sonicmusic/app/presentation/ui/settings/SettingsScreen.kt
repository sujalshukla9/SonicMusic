package com.sonicmusic.app.presentation.ui.settings

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sonicmusic.app.domain.model.FullPlayerStyle
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.model.ThemeMode
import com.sonicmusic.app.presentation.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

private enum class SettingsSheet {
    None, Theme, SeekBarStyle, WifiQuality, CellularQuality, DownloadQuality
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val wifiStreamingQuality by viewModel.wifiStreamingQuality.collectAsState()
    val cellularStreamingQuality by viewModel.cellularStreamingQuality.collectAsState()
    val downloadQuality by viewModel.downloadQuality.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val fullPlayerStyle by viewModel.fullPlayerStyle.collectAsState()
    val dynamicColors by viewModel.dynamicColors.collectAsState()
    val dynamicColorIntensity by viewModel.dynamicColorIntensity.collectAsState()
    val normalizeVolume by viewModel.normalizeVolume.collectAsState()
    val gaplessPlayback by viewModel.gaplessPlayback.collectAsState()
    val crossfadeDuration by viewModel.crossfadeDuration.collectAsState()
    val pauseHistory by viewModel.pauseHistory.collectAsState()
    val skipSilence by viewModel.skipSilence.collectAsState()
    val albumArtBlur by viewModel.albumArtBlur.collectAsState()
    val cacheSize by viewModel.cacheSize.collectAsState()
    val enhancedAudio by viewModel.enhancedAudio.collectAsState()
    
    val regionCode by viewModel.regionCode.collectAsState()
    val countryCode by viewModel.countryCode.collectAsState()
    val countryName by viewModel.countryName.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf(SettingsSheet.None) }

    // Clear cache confirmation dialog
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
            title = { Text("Clear Cache") },
            text = { Text("This will remove all cached audio data. Downloaded songs will not be affected.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearCache()
                        showClearCacheDialog = false
                        scope.launch {
                            snackbarHostState.showSnackbar("Cache cleared successfully")
                        }
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear history confirmation dialog
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
            title = { Text("Clear Search History") },
            text = { Text("This will remove all your search history. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearSearchHistory()
                        showClearHistoryDialog = false
                        scope.launch {
                            snackbarHostState.showSnackbar("Search history cleared")
                        }
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    ) 
                },
                scrollBehavior = scrollBehavior,
                windowInsets = WindowInsets.statusBars
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 16.dp,
                bottom = bottomPadding + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ═══════════════════════════════════════════
            // AUDIO QUALITY SECTION
            // ═══════════════════════════════════════════
            item {
                SettingsGroup(
                    title = "Audio Quality",
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    AudioQualityItem(
                        title = "Wi-Fi Streaming",
                        subtitle = "Audio quality when connected to Wi-Fi",
                        currentQuality = wifiStreamingQuality,
                        onClick = { activeSheet = SettingsSheet.WifiQuality }
                    )
                    SettingsDivider()
                    AudioQualityItem(
                        title = "Cellular Streaming",
                        subtitle = "Audio quality when using mobile data",
                        currentQuality = cellularStreamingQuality,
                        onClick = { activeSheet = SettingsSheet.CellularQuality }
                    )
                    SettingsDivider()
                    AudioQualityItem(
                        title = "Download Quality",
                        subtitle = "Audio quality for offline downloads",
                        currentQuality = downloadQuality,
                        onClick = { activeSheet = SettingsSheet.DownloadQuality }
                    )
                    SettingsDivider()
                    SettingsSwitchItem(
                        title = "Normalize Volume",
                        subtitle = "Adjust volume levels to maintain consistent loudness",
                        checked = normalizeVolume,
                        onCheckedChange = { viewModel.setNormalizeVolume(it) }
                    )
                    SettingsDivider()
                    SettingsSwitchItem(
                        title = "Enhanced Audio (FFmpeg)",
                        subtitle = "Transcode to M4A Lossless via cloud backend",
                        checked = enhancedAudio,
                        onCheckedChange = { viewModel.setEnhancedAudio(it) }
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Info card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "About Audio Quality",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "High-Res streams use more data than High Quality. An external DAC may be required for the best listening experience.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ═══════════════════════════════════════════
            // PLAYBACK SECTION
            // ═══════════════════════════════════════════
            item {
                SettingsGroup(
                    title = "Playback",
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    SettingsSwitchItem(
                        title = "Gapless Playback",
                        subtitle = "Seamlessly transition between songs",
                        checked = gaplessPlayback,
                        onCheckedChange = { viewModel.setGaplessPlayback(it) }
                    )
                    SettingsDivider()
                    SettingsSwitchItem(
                        title = "Skip Silence",
                        subtitle = "Automatically skip silent parts",
                        checked = skipSilence,
                        onCheckedChange = { viewModel.setSkipSilence(it) }
                    )
                    SettingsDivider()
                    SettingsSliderItem(
                        title = "Crossfade Duration",
                        subtitle = "Blend audio when transitioning",
                        value = crossfadeDuration.toFloat(),
                        valueRange = 0f..12f,
                        steps = 11,
                        valueText = if (crossfadeDuration == 0) "Off" else "${crossfadeDuration}s",
                        onValueChange = { viewModel.setCrossfadeDuration(it.toInt()) }
                    )
                    SettingsDivider()
                    SettingsActionItem(
                        title = "Device Equalizer",
                        subtitle = "Open system audio effects panel",
                        icon = Icons.Default.GraphicEq,
                        onClick = {
                            if (!openDeviceEqualizerPanel(context)) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("No device equalizer found")
                                }
                            }
                        }
                    )
                }
            }

            // ═══════════════════════════════════════════
            // REGIONAL SECTION
            // ═══════════════════════════════════════════
            // ═══════════════════════════════════════════
            // REGIONAL SECTION
            // ═══════════════════════════════════════════
            
            item {
                SettingsGroup(
                    title = "Regional",
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    SettingsInfoItem(
                        title = "Country",
                        subtitle = countryName ?: countryCode ?: "Detecting...",
                        icon = Icons.Default.Public,
                        trailingContent = {
                            IconButton(onClick = { viewModel.refreshRegion() }) {
                                Icon(
                                    imageVector = Icons.Default.RestartAlt,
                                    contentDescription = "Refresh Region",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                    SettingsDivider()
                    SettingsInfoItem(
                        title = "Region Code",
                        subtitle = regionCode ?: "Detecting...",
                        icon = Icons.Default.Place
                    )
                }
            }

            // ═══════════════════════════════════════════
            // APPEARANCE SECTION
            // ═══════════════════════════════════════════
            item {
                SettingsGroup(
                    title = "Appearance",
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    SettingsActionItem(
                        title = "Theme",
                        subtitle = "Choose app appearance",
                        value = when (themeMode) {
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                            ThemeMode.SYSTEM -> "System"
                        },
                        onClick = { activeSheet = SettingsSheet.Theme }
                    )
                    SettingsDivider()
                    SettingsActionItem(
                        title = "Seek Bar Style",
                        subtitle = "Choose style for player progress bar",
                        value = fullPlayerStyleMenuLabel(fullPlayerStyle),
                        onClick = { activeSheet = SettingsSheet.SeekBarStyle }
                    )
                    SettingsDivider()
                    SettingsSwitchItem(
                        title = "Dynamic Colors",
                        subtitle = "Interface adapts to current album artwork",
                        checked = dynamicColors,
                        onCheckedChange = { viewModel.setDynamicColors(it) }
                    )
                    if (dynamicColors) {
                        SettingsDivider()
                        SettingsSliderItem(
                            title = "Theme Vibrancy",
                            subtitle = "Adjust the strength of extracted album colors",
                            value = dynamicColorIntensity.toFloat(),
                            valueRange = 0f..100f,
                            steps = 99,
                            valueText = "${dynamicColorIntensity}%",
                            onValueChange = { viewModel.setDynamicColorIntensity(it.toInt()) }
                        )
                    }
                    SettingsDivider()
                    SettingsSwitchItem(
                        title = "Album Art Blur",
                        subtitle = "Show blurred background in player",
                        checked = albumArtBlur,
                        onCheckedChange = { viewModel.setAlbumArtBlur(it) }
                    )
                }
            }

            // ═══════════════════════════════════════════
            // STORAGE & PRIVACY
            // ═══════════════════════════════════════════
            item {
                SettingsGroup(
                    title = "Storage & Privacy",
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    SettingsActionItem(
                        title = "Clear Cache",
                        subtitle = "Using $cacheSize",
                        icon = Icons.Default.Storage,
                        onClick = { showClearCacheDialog = true }
                    )
                    SettingsDivider()
                    SettingsSwitchItem(
                        title = "Pause History",
                        subtitle = "Stop recording listening history",
                        checked = pauseHistory,
                        onCheckedChange = { viewModel.setPauseHistory(it) }
                    )
                    SettingsDivider()
                    SettingsActionItem(
                        title = "Clear Search History",
                        subtitle = "Remove past searches",
                        icon = Icons.Default.DeleteForever,
                        onClick = { showClearHistoryDialog = true }
                    )
                    SettingsDivider()
                    SettingsActionItem(
                        title = "Reset to Defaults",
                        subtitle = "Restore all settings to factory values",
                        icon = Icons.Default.RestartAlt,
                        onClick = {
                            viewModel.resetToDefaults()
                            scope.launch {
                                snackbarHostState.showSnackbar("Settings reset to defaults")
                            }
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
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    SettingsInfoItem(
                        title = "App Version",
                        subtitle = viewModel.appVersion,
                        icon = Icons.Default.Info
                    )
                    SettingsDivider()
                    SettingsActionItem(
                        title = "Source Code",
                        subtitle = "View on GitHub",
                        icon = Icons.Default.Code,
                        onClick = {
                            openUrl(context, "https://github.com/sonicmusic/app")
                        }
                    )
                    SettingsDivider()
                    SettingsActionItem(
                        title = "Privacy Policy",
                        subtitle = "Read our policy",
                        icon = Icons.Default.Policy,
                        onClick = {
                            openUrl(context, "https://sonicmusic.app/privacy")
                        }
                    )
                    SettingsDivider()
                    SettingsActionItem(
                        title = "Help & Feedback",
                        subtitle = "Get support",
                        icon = Icons.AutoMirrored.Filled.HelpOutline,
                        onClick = {
                            openUrl(context, "https://sonicmusic.app/help")
                        }
                    )
                }
            }
            
            // Bottom spacing
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (activeSheet != SettingsSheet.None) {
        ModalBottomSheet(
            onDismissRequest = { activeSheet = SettingsSheet.None },
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = when (activeSheet) {
                        SettingsSheet.Theme -> "Select Theme"
                        SettingsSheet.SeekBarStyle -> "Seek Bar Style"
                        SettingsSheet.WifiQuality -> "Wi-Fi Streaming Quality"
                        SettingsSheet.CellularQuality -> "Mobile Streaming Quality"
                        SettingsSheet.DownloadQuality -> "Download Quality"
                        else -> ""
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                )
                
                when (activeSheet) {
                    SettingsSheet.Theme -> {
                        ThemeMode.entries.forEach { mode ->
                             OptionItem(
                                 text = when(mode) {
                                     ThemeMode.LIGHT -> "Light"
                                     ThemeMode.DARK -> "Dark"
                                     ThemeMode.SYSTEM -> "System"
                                 },
                                 selected = themeMode == mode,
                                 onClick = {
                                     viewModel.setThemeMode(mode)
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
                    SettingsSheet.WifiQuality -> {
                        menuQualityEntries.forEach { quality ->
                            QualityOptionItem(
                                quality = quality,
                                selected = wifiStreamingQuality == quality,
                                onClick = {
                                    viewModel.setWifiQuality(quality)
                                    activeSheet = SettingsSheet.None
                                }
                            )
                        }
                    }
                    SettingsSheet.CellularQuality -> {
                        menuQualityEntries.forEach { quality ->
                            QualityOptionItem(
                                quality = quality,
                                selected = cellularStreamingQuality == quality,
                                onClick = {
                                    viewModel.setCellularQuality(quality)
                                    activeSheet = SettingsSheet.None
                                }
                            )
                        }
                    }
                    SettingsSheet.DownloadQuality -> {
                        menuQualityEntries.forEach { quality ->
                            QualityOptionItem(
                                quality = quality,
                                selected = downloadQuality == quality,
                                onClick = {
                                    viewModel.setDownloadQuality(quality)
                                    activeSheet = SettingsSheet.None
                                }
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun OptionItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(text) },
        trailingContent = {
            if (selected) {
                 Icon(
                     imageVector = Icons.Default.Check,
                     contentDescription = "Selected",
                     tint = MaterialTheme.colorScheme.primary
                 )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        ),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun QualityOptionItem(
    quality: StreamQuality,
    selected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(qualityMenuLabel(quality)) },
        supportingContent = { Text(qualityMenuDescription(quality)) },
        leadingContent = {
             if (quality == StreamQuality.BEST || quality == StreamQuality.LOSSLESS) {
                 Surface(
                     color = MaterialTheme.colorScheme.primaryContainer,
                     shape = MaterialTheme.shapes.extraSmall,
                 ) {
                     Text(
                         text = qualityBadge(quality),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onPrimaryContainer,
                         modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                     )
                 }
             }
        },
        trailingContent = {
            if (selected) {
                 Icon(
                     imageVector = Icons.Default.Check,
                     contentDescription = "Selected",
                     tint = MaterialTheme.colorScheme.primary
                 )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        ),
        modifier = Modifier.clickable(onClick = onClick)
    )
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
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    context.startActivity(intent)
}

private fun openDeviceEqualizerPanel(context: Context): Boolean {
    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
    return runCatching { context.startActivity(intent) }.isSuccess
}

@Composable
private fun SettingsSwitchItem(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    )
}

@Composable
private fun SettingsActionItem(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit
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
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
        trailingContent = {
             Row(verticalAlignment = Alignment.CenterVertically) {
                 if (value != null) {
                     Text(
                         text = value,
                         style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.primary
                     )
                     Spacer(modifier = Modifier.width(4.dp))
                 }
                 Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
             }
        }
    )
}

@Composable
private fun SettingsInfoItem(
    title: String,
    subtitle: String,
    icon: ImageVector? = null,
    trailingContent: @Composable (() -> Unit)? = null
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle) },
        leadingContent = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun SettingsSliderItem(
    title: String,
    subtitle: String? = null,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: String,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title, 
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
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
private fun AudioQualityItem(
    title: String,
    subtitle: String? = null,
    currentQuality: StreamQuality,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (currentQuality.isHighRes || currentQuality.isLossless) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            text = qualityBadge(currentQuality),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = qualityMenuLabel(currentQuality),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    )
}

private val menuQualityEntries = listOf(
    StreamQuality.LOW,
    StreamQuality.MEDIUM,
    StreamQuality.HIGH,
    StreamQuality.BEST,
    StreamQuality.LOSSLESS
)

private fun fullPlayerStyleMenuLabel(style: FullPlayerStyle): String {
    return when (style) {
        FullPlayerStyle.NORMAL -> "Normal"
        FullPlayerStyle.WAVY -> "Wavy"
    }
}

private fun qualityMenuLabel(quality: StreamQuality): String {
    return when (quality) {
        StreamQuality.LOW -> "Low"
        StreamQuality.MEDIUM -> "Medium"
        StreamQuality.HIGH -> "High"
        StreamQuality.BEST -> "Very High"
        StreamQuality.LOSSLESS -> "Lossless"
    }
}

private fun qualityMenuDescription(quality: StreamQuality): String {
    return when (quality) {
        StreamQuality.LOW -> "Lower quality, saves data"
        StreamQuality.MEDIUM -> "Balanced quality and data usage"
        StreamQuality.HIGH -> "High quality streaming"
        StreamQuality.BEST -> "High-res quality with efficient data usage"
        StreamQuality.LOSSLESS -> "Highest quality, uses more data"
    }
}

private fun qualityBadge(quality: StreamQuality): String {
    return when (quality) {
        StreamQuality.LOSSLESS -> "LL"
        StreamQuality.BEST -> "VH"
        StreamQuality.LOW,
        StreamQuality.MEDIUM,
        StreamQuality.HIGH -> ""
    }
}
