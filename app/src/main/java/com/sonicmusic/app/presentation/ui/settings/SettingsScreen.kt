package com.sonicmusic.app.presentation.ui.settings

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.domain.model.FullPlayerStyle
import com.sonicmusic.app.domain.model.ThemeMode
import com.sonicmusic.app.domain.model.DarkMode
import com.sonicmusic.app.presentation.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

private enum class SettingsSheet {
    None, Theme, DarkMode, SeekBarStyle
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onNavigateBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Appearance States
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val darkMode by viewModel.darkMode.collectAsStateWithLifecycle()
    val dynamicColors by viewModel.dynamicColors.collectAsStateWithLifecycle()
    val dynamicColorIntensity by viewModel.dynamicColorIntensity.collectAsStateWithLifecycle()
    val fullPlayerStyle by viewModel.fullPlayerStyle.collectAsStateWithLifecycle()
    
    // Playback States
    val normalizeVolume by viewModel.normalizeVolume.collectAsStateWithLifecycle()
    val gaplessPlayback by viewModel.gaplessPlayback.collectAsStateWithLifecycle()
    val crossfadeDuration by viewModel.crossfadeDuration.collectAsStateWithLifecycle()
    val skipSilence by viewModel.skipSilence.collectAsStateWithLifecycle()
    val autoQueueSimilar by viewModel.autoQueueSimilar.collectAsStateWithLifecycle()
    
    // Bass states
    val songBassEnabled by viewModel.songBassEnabled.collectAsStateWithLifecycle()
    val songBassStrengthPercent by viewModel.songBassStrengthPercent.collectAsStateWithLifecycle()
    
    // Cache States
    val cacheSize by viewModel.cacheSize.collectAsStateWithLifecycle()
    
    // Region States
    val countryName by viewModel.countryName.collectAsStateWithLifecycle()
    val countryCode by viewModel.countryCode.collectAsStateWithLifecycle()
    
    // Update States
    val appUpdateState by viewModel.appUpdateState.collectAsStateWithLifecycle()
    
    var activeSheet by remember { mutableStateOf(SettingsSheet.None) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val colorScheme = MaterialTheme.colorScheme

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                windowInsets = WindowInsets.statusBars
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            colorScheme.surface,
                            colorScheme.surfaceContainerLow
                        )
                    )
                ),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 16.dp,
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
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    SettingsActionItem(
                        title = "Select Theme",
                        subtitle = when(themeMode) {
                            ThemeMode.DEFAULT -> "Default"
                            ThemeMode.DYNAMIC -> "Dynamic"
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
                    SettingsDivider()
                    SettingsSwitchItem(
                        title = "Dynamic Colors",
                        subtitle = "Extract primary colors from current song artwork",
                        checked = dynamicColors,
                        onCheckedChange = { viewModel.setDynamicColors(it) }
                    )
                    if (dynamicColors) {
                        SettingsDivider()
                        SettingsSliderItem(
                            title = "Theme Color Intensity",
                            value = dynamicColorIntensity.toFloat(),
                            onValueChange = { viewModel.setDynamicColorIntensity(it.toInt()) },
                            valueRange = 0f..100f,
                            steps = 100,
                            valueText = "$dynamicColorIntensity%"
                        )
                    }
                    SettingsDivider()
                    SettingsActionItem(
                        title = "Player Style",
                        subtitle = fullPlayerStyleMenuLabel(fullPlayerStyle),
                        icon = Icons.Rounded.MusicNote,
                        onClick = { activeSheet = SettingsSheet.SeekBarStyle }
                    )
                }
            }

            // ═══════════════════════════════════════════
            // PLAYBACK & AUDIO SECTION
            // ═══════════════════════════════════════════
            item {
                SettingsGroup(
                    title = "Playback",
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    SettingsSwitchItem(
                        title = "Normalize Volume",
                        subtitle = "Reduce peak loudness levels to protect hearing",
                        checked = normalizeVolume,
                        onCheckedChange = { viewModel.setNormalizeVolume(it) }
                    )
                    SettingsDivider()
                    SettingsSwitchItem(
                        title = "Gapless Playback",
                        subtitle = "Pre-buffer next track for seamless audio transition",
                        checked = gaplessPlayback,
                        onCheckedChange = { viewModel.setGaplessPlayback(it) }
                    )
                    SettingsDivider()
                    SettingsSliderItem(
                        title = "Crossfade Duration",
                        value = crossfadeDuration.toFloat(),
                        onValueChange = { viewModel.setCrossfadeDuration(it.toInt()) },
                        valueRange = 0f..12f,
                        steps = 12,
                        valueText = if (crossfadeDuration > 0) "${crossfadeDuration}s" else "Disabled"
                    )
                    SettingsDivider()
                    SettingsSwitchItem(
                        title = "Skip Silence",
                        subtitle = "Automatically skip silent frames within tracks",
                        checked = skipSilence,
                        onCheckedChange = { viewModel.setSkipSilence(it) }
                    )
                    SettingsDivider()
                    SettingsSwitchItem(
                        title = "Bass Boost",
                        subtitle = "Enhance lower audio frequencies dynamically",
                        checked = songBassEnabled,
                        onCheckedChange = { viewModel.setSongBassEnabled(it) }
                    )
                    if (songBassEnabled) {
                        SettingsDivider()
                        SettingsSliderItem(
                            title = "Bass Strength",
                            value = songBassStrengthPercent.toFloat(),
                            onValueChange = { viewModel.setSongBassStrength(it.toInt()) },
                            valueRange = 0f..100f,
                            steps = 100,
                            valueText = "$songBassStrengthPercent%"
                        )
                    }
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
                SettingsGroup(
                    title = "Storage & Downloads",
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    SettingsActionItem(
                        title = "Clear Cache",
                        subtitle = "Cached songs size: $cacheSize",
                        icon = Icons.Rounded.DeleteForever,
                        onClick = {
                            viewModel.clearCache()
                            scope.launch {
                                snackbarHostState.showSnackbar("Cache cleared successfully")
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
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    SettingsActionItem(
                        title = "Content Country",
                        subtitle = countryName ?: countryCode ?: "Detecting region...",
                        icon = Icons.Rounded.Public,
                        onClick = {
                            viewModel.refreshRegion()
                            scope.launch {
                                snackbarHostState.showSnackbar("Region refresh requested")
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
            
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Modal Bottom Sheets for choices
    if (activeSheet != SettingsSheet.None) {
        ModalBottomSheet(
            onDismissRequest = { activeSheet = SettingsSheet.None },
            containerColor = colorScheme.surface,
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
                        SettingsSheet.DarkMode -> "Dark Mode"
                        SettingsSheet.SeekBarStyle -> "Seek Bar Style"
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
                                    ThemeMode.DYNAMIC -> "Dynamic"
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
                    else -> {}
                }
            }
        }
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
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
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
private fun SettingsSwitchItem(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
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
        modifier = modifier.clickable { onCheckedChange(!checked) }
    )
}

@Composable
private fun SettingsActionItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    trailingContent: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier
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
        modifier = modifier.clickable(onClick = onClick)
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
            containerColor = Color.Transparent,
            headlineColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        ),
        modifier = Modifier.clickable(onClick = onClick)
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