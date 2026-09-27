package com.sonicmusic.app.features.settings.presentation

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sonicmusic.app.domain.model.FullPlayerStyle
import com.sonicmusic.app.domain.model.ThemeMode
import com.sonicmusic.app.domain.model.DarkMode
import com.sonicmusic.app.domain.model.ThumbnailShape
import com.sonicmusic.app.domain.model.PlayerControlStyle
import com.sonicmusic.app.theme.LocalThumbnailShape
import com.sonicmusic.app.theme.toComposeShape
import com.sonicmusic.app.theme.Dimens
import com.sonicmusic.app.shared.components.AlbumArtThumbnail
import com.sonicmusic.app.features.player.presentation.PlayerViewModel
import com.sonicmusic.app.features.settings.presentation.SettingsViewModel
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

private enum class SettingsSheet {
    None, Theme, DarkMode, SeekBarStyle, AlbumArtShape
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

    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
        onResult = { uri ->
            if (uri != null) {
                viewModel.backupDatabase(
                    uri = uri,
                    onSuccess = {
                        scope.launch {
                            snackbarHostState.showSnackbar("Database backup created successfully")
                        }
                    },
                    onError = { error ->
                        scope.launch {
                            snackbarHostState.showSnackbar("Backup failed: $error")
                        }
                    }
                )
            }
        }
    )

    var showRestoreConfirmation by remember { mutableStateOf<Uri?>(null) }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                showRestoreConfirmation = uri
            }
        }
    )

    if (showRestoreConfirmation != null) {
        val restoreUri = showRestoreConfirmation!!
        AlertDialog(
            onDismissRequest = { showRestoreConfirmation = null },
            title = { Text("Restore Database?") },
            text = { Text("Restoring will overwrite your current playlists, library history, and local settings. The app will close to complete the process safely. Are you sure you want to continue?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirmation = null
                        viewModel.restoreDatabase(
                            uri = restoreUri,
                            onSuccess = {
                                android.os.Process.killProcess(android.os.Process.myPid())
                            },
                            onError = { error ->
                                scope.launch {
                                    snackbarHostState.showSnackbar("Restore failed: $error")
                                }
                            }
                        )
                    }
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmation = null }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Appearance States
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val darkMode by viewModel.darkMode.collectAsStateWithLifecycle()
    val dynamicColors by viewModel.dynamicColors.collectAsStateWithLifecycle()
    val dynamicColorIntensity by viewModel.dynamicColorIntensity.collectAsStateWithLifecycle()
    val fullPlayerStyle by viewModel.fullPlayerStyle.collectAsStateWithLifecycle()
    val thumbnailShape by viewModel.thumbnailShape.collectAsStateWithLifecycle()
    val playerControlStyle by viewModel.playerControlStyle.collectAsStateWithLifecycle()
    val currentSong by playerViewModel.currentSong.collectAsStateWithLifecycle()
    
    // Playback States
    val normalizeVolume by viewModel.normalizeVolume.collectAsStateWithLifecycle()
    val gaplessPlayback by viewModel.gaplessPlayback.collectAsStateWithLifecycle()
    val crossfadeDuration by viewModel.crossfadeDuration.collectAsStateWithLifecycle()
    val skipSilence by viewModel.skipSilence.collectAsStateWithLifecycle()
    val autoQueueSimilar by viewModel.autoQueueSimilar.collectAsStateWithLifecycle()
    
    
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
        Box(
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
                        valueText = if (crossfadeDuration > 0) "${crossfadeDuration}s" else " Disabled"
                    )
                    SettingsDivider()
                    SettingsSwitchItem(
                        title = "Skip Silence",
                        subtitle = "Automatically skip silent frames within tracks",
                        checked = skipSilence,
                        onCheckedChange = { viewModel.setSkipSilence(it) }
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
                SettingsGroup(
                    title = "Storage & Downloads",
                    modifier = Modifier.padding(horizontal = Dimens.screenPadding)
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
                    modifier = Modifier.padding(horizontal = Dimens.screenPadding)
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
            // DATA & BACKUP SECTION
            // ═══════════════════════════════════════════
            item {
                SettingsGroup(
                    title = "Data & Backup",
                    modifier = Modifier.padding(horizontal = Dimens.screenPadding)
                ) {
                    SettingsActionItem(
                        title = "Backup Database",
                        subtitle = "Export playlists and history to storage",
                        icon = Icons.Rounded.Backup,
                        onClick = {
                            backupLauncher.launch("sonic_music_backup.db")
                        }
                    )
                    SettingsDivider()
                    SettingsActionItem(
                        title = "Restore Database",
                        subtitle = "Import playlists and history from a backup file",
                        icon = Icons.Rounded.Restore,
                        onClick = {
                            restoreLauncher.launch(arrayOf("application/octet-stream", "*/*"))
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
            containerColor = colorScheme.surface,
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
                        SettingsSheet.SeekBarStyle -> "Seek Bar Style"
                        SettingsSheet.AlbumArtShape -> "Album Art Shape"
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
                                    modifier = Modifier
                                        .size(160.dp)
                                        .shadow(12.dp, previewShape.toComposeShape()),
                                    highQuality = true
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(160.dp)
                                        .shadow(12.dp, previewShape.toComposeShape())
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
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(colorScheme.onSurface.copy(alpha = 0.12f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(0.4f)
                                    .background(if (isSelected) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.4f))
                            )
                        }
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(5) { index ->
                                val isPlayPause = index == 2
                                val size = if (isPlayPause) 20.dp else 12.dp
                                val color = if (isPlayPause) {
                                    if (isSelected) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.4f)
                                } else {
                                    colorScheme.onSurface.copy(alpha = 0.2f)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(size)
                                        .clip(CircleShape)
                                        .background(color)
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(0.7f)
                                    .height(18.dp)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(if (isSelected) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.4f))
                            )
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colorScheme.onSurface.copy(alpha = 0.15f))
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colorScheme.onSurface.copy(alpha = 0.15f))
                            )
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(3.dp)
                                    .clip(CircleShape)
                                    .background(colorScheme.onSurface.copy(alpha = 0.12f)),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(0.4f)
                                        .background(if (isSelected) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.4f))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}