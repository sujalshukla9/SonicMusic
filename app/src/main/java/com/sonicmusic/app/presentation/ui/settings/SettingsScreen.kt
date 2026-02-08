package com.sonicmusic.app.presentation.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Policy
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.model.ThemeMode
import com.sonicmusic.app.presentation.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val streamQuality by viewModel.streamQuality.collectAsState()
    val downloadQuality by viewModel.downloadQuality.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val dynamicColors by viewModel.dynamicColors.collectAsState()
    val normalizeVolume by viewModel.normalizeVolume.collectAsState()
    val gaplessPlayback by viewModel.gaplessPlayback.collectAsState()
    val crossfadeDuration by viewModel.crossfadeDuration.collectAsState()
    val pauseHistory by viewModel.pauseHistory.collectAsState()
    val skipSilence by viewModel.skipSilence.collectAsState()
    val albumArtBlur by viewModel.albumArtBlur.collectAsState()
    
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

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
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.headlineMedium
                    ) 
                },
                windowInsets = TopAppBarDefaults.windowInsets
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // ═══════════════════════════════════════════
            // AUDIO QUALITY SECTION
            // ═══════════════════════════════════════════
            item {
                SettingsSectionTitle("Audio Quality")
            }
            
            item {
                SettingsDropdownItem(
                    title = "Streaming Quality",
                    subtitle = "Quality used when streaming over network",
                    currentValue = streamQuality.displayName,
                    options = StreamQuality.entries.map { it.displayName },
                    selectedIndex = StreamQuality.entries.indexOf(streamQuality),
                    onSelect = { index ->
                        viewModel.setStreamQuality(StreamQuality.entries[index])
                    }
                )
            }

            item {
                SettingsDropdownItem(
                    title = "Download Quality",
                    subtitle = "Quality used when downloading for offline playback",
                    currentValue = downloadQuality.displayName,
                    options = StreamQuality.entries.map { it.displayName },
                    selectedIndex = StreamQuality.entries.indexOf(downloadQuality),
                    onSelect = { index ->
                        viewModel.setDownloadQuality(StreamQuality.entries[index])
                    }
                )
            }

            item {
                SettingsSwitchItem(
                    title = "Normalize Volume",
                    subtitle = "Adjust volume levels to maintain consistent loudness",
                    checked = normalizeVolume,
                    onCheckedChange = { viewModel.setNormalizeVolume(it) }
                )
            }

            // ═══════════════════════════════════════════
            // PLAYBACK SECTION
            // ═══════════════════════════════════════════
            item {
                SettingsSectionTitle("Playback")
            }

            item {
                SettingsSwitchItem(
                    title = "Gapless Playback",
                    subtitle = "Seamlessly transition between songs without silence",
                    checked = gaplessPlayback,
                    onCheckedChange = { viewModel.setGaplessPlayback(it) }
                )
            }
            
            item {
                SettingsSwitchItem(
                    title = "Skip Silence",
                    subtitle = "Automatically skip silent parts in songs",
                    checked = skipSilence,
                    onCheckedChange = { viewModel.setSkipSilence(it) }
                )
            }

            item {
                SettingsSliderItem(
                    title = "Crossfade Duration",
                    subtitle = "Blend audio when transitioning between songs",
                    value = crossfadeDuration.toFloat(),
                    valueRange = 0f..12f,
                    steps = 11,
                    valueText = if (crossfadeDuration == 0) "Off" else "${crossfadeDuration}s",
                    onValueChange = { viewModel.setCrossfadeDuration(it.toInt()) }
                )
            }

            // ═══════════════════════════════════════════
            // APPEARANCE SECTION
            // ═══════════════════════════════════════════
            item {
                SettingsSectionTitle("Appearance")
            }

            item {
                SettingsDropdownItem(
                    title = "Theme",
                    subtitle = "Choose light, dark, or follow system theme",
                    currentValue = when (themeMode) {
                        ThemeMode.LIGHT -> "Light"
                        ThemeMode.DARK -> "Dark"
                        ThemeMode.SYSTEM -> "System Default"
                    },
                    options = listOf("Light", "Dark", "System Default"),
                    selectedIndex = ThemeMode.entries.indexOf(themeMode),
                    onSelect = { index ->
                        viewModel.setThemeMode(ThemeMode.entries[index])
                    }
                )
            }

            item {
                SettingsSwitchItem(
                    title = "Dynamic Colors",
                    subtitle = "Use Material You colors from your wallpaper",
                    checked = dynamicColors,
                    onCheckedChange = { viewModel.setDynamicColors(it) }
                )
            }
            
            item {
                SettingsSwitchItem(
                    title = "Album Art Blur",
                    subtitle = "Show blurred album art in player background",
                    checked = albumArtBlur,
                    onCheckedChange = { viewModel.setAlbumArtBlur(it) }
                )
            }

            // ═══════════════════════════════════════════
            // STORAGE & CACHE SECTION
            // ═══════════════════════════════════════════
            item {
                SettingsSectionTitle("Storage & Cache")
            }

            item {
                SettingsActionItem(
                    title = "Clear Cache",
                    subtitle = "Remove cached audio data to free up space",
                    icon = Icons.Default.Storage,
                    onClick = { showClearCacheDialog = true }
                )
            }

            // ═══════════════════════════════════════════
            // PRIVACY SECTION
            // ═══════════════════════════════════════════
            item {
                SettingsSectionTitle("Privacy")
            }

            item {
                SettingsSwitchItem(
                    title = "Pause History",
                    subtitle = "Temporarily stop recording playback history",
                    checked = pauseHistory,
                    onCheckedChange = { viewModel.setPauseHistory(it) }
                )
            }

            item {
                SettingsActionItem(
                    title = "Clear Search History",
                    subtitle = "Remove all your search history",
                    icon = Icons.Default.DeleteForever,
                    onClick = { showClearHistoryDialog = true }
                )
            }

            // ═══════════════════════════════════════════
            // ABOUT SECTION
            // ═══════════════════════════════════════════
            item {
                SettingsSectionTitle("About")
            }

            item {
                SettingsInfoItem(
                    title = "App Version",
                    subtitle = "1.0.0 (Build 1)",
                    icon = Icons.Default.Info
                )
            }
            
            item {
                SettingsActionItem(
                    title = "Source Code",
                    subtitle = "View project on GitHub",
                    icon = Icons.Default.Code,
                    onClick = {
                        openUrl(context, "https://github.com/sonicmusic/app")
                    }
                )
            }
            
            item {
                SettingsActionItem(
                    title = "Privacy Policy",
                    subtitle = "Read our privacy policy",
                    icon = Icons.Default.Policy,
                    onClick = {
                        openUrl(context, "https://sonicmusic.app/privacy")
                    }
                )
            }
            
            item {
                SettingsActionItem(
                    title = "Help & Feedback",
                    subtitle = "Get help or send feedback",
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                    onClick = {
                        openUrl(context, "https://sonicmusic.app/help")
                    }
                )
            }
            
            // Bottom spacing
            item {
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    context.startActivity(intent)
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsSwitchItem(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    )
}

@Composable
private fun SettingsActionItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
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
        modifier = Modifier.clickable(onClick = onClick),
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
private fun SettingsInfoItem(
    title: String,
    subtitle: String,
    icon: ImageVector? = null
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun SettingsDropdownItem(
    title: String,
    subtitle: String? = null,
    currentValue: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = subtitle?.let { { Text(it) } },
            modifier = Modifier.clickable { expanded = true },
            trailingContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currentValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                    trailingIcon = {
                        if (index == selectedIndex) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )
            }
        }
    }
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
                    style = MaterialTheme.typography.bodyLarge
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