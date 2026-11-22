package com.sonicmusic.app.features.settings.presentation

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.sonicmusic.app.R
import com.sonicmusic.app.features.player.notification.MediaNotificationProvider
import com.sonicmusic.app.features.updates.receiver.UpdateNotifications
import com.sonicmusic.app.shared.components.ExpressiveNotice
import com.sonicmusic.app.shared.components.ExpressiveSnackbarHost
import com.sonicmusic.app.shared.components.NoticeIcon
import com.sonicmusic.app.theme.Dimens
import com.sonicmusic.app.theme.pressScale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationSettingsScreen(bottomPadding: Dp, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val notifications = remember(context) { NotificationManagerCompat.from(context) }
    var enabled by remember(notifications) { mutableStateOf(notifications.areNotificationsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = notifications.areNotificationsEnabled()
    }

    val snackbarState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val openError = stringResource(R.string.notification_settings_open_error)
    fun openSettings(channelId: String? = null) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = channelId?.let { manager?.getNotificationChannel(it) }
        val appSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        val channelSettings = channel?.let {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, it.id)
        }
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        val opened = listOfNotNull(channelSettings, appSettings, details)
            .any { runCatching { context.startActivity(it) }.isSuccess }
        if (!opened) scope.launch { snackbarState.showSnackbar(openError) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notification_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.notification_settings_back))
                    }
                }
            )
        },
        snackbarHost = { ExpressiveSnackbarHost(snackbarState) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = Dimens.maxContentWidth).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(PaddingValues(start = Dimens.screenPadding, end = Dimens.screenPadding,
                        top = 12.dp, bottom = bottomPadding + 24.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExpressiveNotice(
                    icon = if (enabled) Icons.Rounded.Notifications else Icons.Rounded.NotificationsOff,
                    title = stringResource(
                        if (enabled) R.string.notification_settings_enabled else R.string.notification_settings_disabled
                    ),
                    message = stringResource(
                        if (enabled) R.string.notification_settings_manage_hint else R.string.notification_settings_enable_hint
                    ),
                    actionLabel = stringResource(R.string.notification_settings_open),
                    onAction = { openSettings() }
                )
                NotificationChannelRow(
                    icon = Icons.Rounded.Headphones,
                    title = stringResource(R.string.notification_settings_playback),
                    subtitle = stringResource(R.string.notification_settings_playback_summary),
                    onClick = { openSettings(MediaNotificationProvider.CHANNEL_ID) }
                )
                NotificationChannelRow(
                    icon = Icons.Rounded.CloudDownload,
                    title = stringResource(R.string.notification_settings_updates),
                    subtitle = stringResource(R.string.notification_settings_updates_summary),
                    onClick = { openSettings(UpdateNotifications.CHANNEL) }
                )
            }
        }
    }
}

@Composable
private fun NotificationChannelRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().pressScale(interaction),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large
    ) {
        ListItem(
            headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(subtitle) },
            leadingContent = { NoticeIcon(icon) },
            trailingContent = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}
