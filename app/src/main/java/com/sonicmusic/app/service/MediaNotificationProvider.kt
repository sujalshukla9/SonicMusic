package com.sonicmusic.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.sonicmusic.app.R
import com.sonicmusic.app.presentation.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Modern Media Notification Provider
 * 
 * Creates a modern, styled media notification similar to popular music apps like:
 * - Spotify
 * - YouTube Music
 * - Apple Music
 * 
 * Features:
 * - Full album art display
 * - Custom action buttons with proper icons
 * - MediaStyle notification for system integration
 * - Dynamic color extraction from album art (Android 12+)
 * - Seekbar in notification (Android 13+)
 * - Proper metadata display
 */
@OptIn(UnstableApi::class)
class MediaNotificationProvider(
    private val context: Context,
) : MediaNotification.Provider {
    
    companion object {
        private const val TAG = "MediaNotificationProvider"
        const val CHANNEL_ID = "sonic_music_playback"
        const val NOTIFICATION_ID = 1
        
        // Custom action commands
        const val ACTION_TOGGLE_SHUFFLE = "sonic_music_toggle_shuffle"
        const val ACTION_TOGGLE_REPEAT = "sonic_music_toggle_repeat"
        const val ACTION_CLOSE = "sonic_music_close"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cachedArtwork: Bitmap? = null
    private var cachedArtworkUri: String? = null
    
    init {
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows currently playing music"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
    
    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: com.google.common.collect.ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
        val player = mediaSession.player
        val mediaItem = player.currentMediaItem
        val metadata = mediaItem?.mediaMetadata
        val artworkUri = metadata?.artworkUri?.toString()
        
        // Load artwork asynchronously
        loadArtworkAsync(artworkUri) {
            // Callback to refresh notification when artwork is loaded
            onNotificationChangedCallback.onNotificationChanged(
                createNotificationInternal(mediaSession, it)
            )
        }
        
        return createNotificationInternal(mediaSession, cachedArtwork)
    }
    
    private fun createNotificationInternal(
        mediaSession: MediaSession,
        artwork: Bitmap?
    ): MediaNotification {
        val player = mediaSession.player
        val mediaItem = player.currentMediaItem
        val metadata = mediaItem?.mediaMetadata
        
        val title = metadata?.title?.toString() ?: "Unknown"
        val artist = metadata?.artist?.toString() ?: "Unknown Artist"
        val album = metadata?.albumTitle?.toString()
        
        val isPlaying = player.isPlaying
        // Only show Next if there is a next item
        val hasNext = player.hasNextMediaItem()
        
        // Create content intent to open app
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Create delete intent for swipe dismiss
        val deleteIntent = PendingIntent.getService(
            context,
            100,
            Intent(context, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        // Lists to hold actions and their indices for compact view
        val actions = mutableListOf<NotificationCompat.Action>()
        val compactViewIndices = mutableListOf<Int>()
        
        val shuffleEnabled = player.shuffleModeEnabled
        val repeatMode = player.repeatMode
        
        // 1. Shuffle Action (Index 0) - Always added, but NOT in compact view usually
        actions.add(
            createAction(
                R.drawable.ic_notification_shuffle,
                if (shuffleEnabled) "Shuffle On" else "Shuffle Off",
                PlaybackService.ACTION_TOGGLE_SHUFFLE,
                0,
                true // Always enabled to allow toggling
            )
        )
        
        // 2. Previous Action (Index 1) - In Compact View
        // We always add Previous, but if no previous item, we could disable it. 
        // For consistent UI, we keep it enabled or check capability.
        // Android guidelines say "Previous" should be present.
        actions.add(
            createAction(
                R.drawable.ic_notification_prev,
                "Previous",
                PlaybackService.ACTION_PREVIOUS,
                1,
                true 
            )
        )
        compactViewIndices.add(actions.lastIndex)
        
        // 3. Play/Pause Action (Index 2) - In Compact View
        actions.add(
            createAction(
                if (isPlaying) R.drawable.ic_notification_pause else R.drawable.ic_notification_play,
                if (isPlaying) "Pause" else "Play",
                if (isPlaying) PlaybackService.ACTION_PAUSE else PlaybackService.ACTION_PLAY,
                2,
                true
            )
        )
        compactViewIndices.add(actions.lastIndex)
        
        // 4. Next Action (Index 3) - ALWAYS in Compact View
        // We always show Next to allow triggering "load more" or just solely for UI consistency
        actions.add(
            createAction(
                R.drawable.ic_notification_next,
                "Next",
                PlaybackService.ACTION_NEXT,
                3,
                true
            )
        )
        compactViewIndices.add(actions.lastIndex)
        
        // 5. Repeat Action (Index 4 or 3) - Expanded view only
        val repeatIcon = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> R.drawable.ic_notification_repeat_one
            Player.REPEAT_MODE_ALL -> R.drawable.ic_notification_repeat
            else -> R.drawable.ic_notification_repeat
        }
        val repeatLabel = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> "Repeat One"
            Player.REPEAT_MODE_ALL -> "Repeat All"
            else -> "Repeat Off"
        }
        actions.add(
            createAction(
                repeatIcon,
                repeatLabel,
                PlaybackService.ACTION_TOGGLE_REPEAT,
                actions.size, // Dynamic request code
                true
            )
        )
        
        // Build notification
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText(album)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setLargeIcon(artwork)
            .setContentIntent(contentIntent)
            .setDeleteIntent(deleteIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionCompatToken)
                    .setShowActionsInCompactView(*compactViewIndices.toIntArray())
                    .setShowCancelButton(!isPlaying)
                    .setCancelButtonIntent(deleteIntent)
            )
            
        // Add all actions to builder
        actions.forEach { action -> builder.addAction(action) }
        
        // Set color extraction from artwork (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && artwork != null) {
            // Use dynamic color extraction
            builder.setColorized(true)
        }
        
        return MediaNotification(NOTIFICATION_ID, builder.build())
    }
    
    private fun createAction(
        iconRes: Int,
        title: String,
        action: String,
        requestCode: Int,
        enabled: Boolean
    ): NotificationCompat.Action {
        val intent = Intent(context, PlaybackService::class.java).apply {
            this.action = action
        }
        val pendingIntent = PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Action.Builder(
            iconRes,
            title,
            if (enabled) pendingIntent else null
        ).build()
    }
    
    private fun loadArtworkAsync(uri: String?, onLoaded: (Bitmap?) -> Unit) {
        if (uri == null) {
            cachedArtwork = null
            cachedArtworkUri = null
            return
        }
        
        // Return cached artwork if URI matches
        if (uri == cachedArtworkUri && cachedArtwork != null) {
            return
        }
        
        scope.launch {
            try {
                val loader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(uri)
                    .allowHardware(false)
                    .size(512, 512) // Good size for notification
                    .build()
                
                val result = loader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                    cachedArtwork = bitmap
                    cachedArtworkUri = uri
                    
                    // Notify on main thread
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        onLoaded(bitmap)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load artwork: ${e.message}")
            }
        }
    }
    
    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle
    ): Boolean {
        return when (action) {
            ACTION_TOGGLE_SHUFFLE -> {
                session.player.shuffleModeEnabled = !session.player.shuffleModeEnabled
                true
            }
            ACTION_TOGGLE_REPEAT -> {
                val newMode = when (session.player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                session.player.repeatMode = newMode
                true
            }
            ACTION_CLOSE -> {
                session.player.stop()
                true
            }
            else -> false
        }
    }
    
    fun release() {
        cachedArtwork = null
        cachedArtworkUri = null
    }
}
