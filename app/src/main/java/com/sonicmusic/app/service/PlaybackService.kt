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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.common.collect.ImmutableList
import com.sonicmusic.app.R
import com.sonicmusic.app.domain.usecase.EqualizerManager
import com.sonicmusic.app.presentation.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Media Playback Service
 * 
 * Handles all audio playback using ExoPlayer and Media3.
 * Features:
 * - Modern styled notification (Spotify/YouTube Music style)
 * - Media session for system integration
 * - Foreground notification with album art
 * - Audio focus handling
 * - Headphone disconnect handling
 * - Queue management
 * - Error recovery
 * - Audio Equalizer integration
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    
    @Inject
    lateinit var equalizerManager: EqualizerManager

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "sonic_music_playback"
        const val NOTIFICATION_ID = 1

        const val ACTION_PLAY = "com.sonicmusic.PLAY"
        const val ACTION_PAUSE = "com.sonicmusic.PAUSE"
        const val ACTION_NEXT = "com.sonicmusic.NEXT"
        const val ACTION_PREVIOUS = "com.sonicmusic.PREVIOUS"
        const val ACTION_STOP = "com.sonicmusic.STOP"
        const val ACTION_TOGGLE_SHUFFLE = "com.sonicmusic.TOGGLE_SHUFFLE"
        const val ACTION_TOGGLE_REPEAT = "com.sonicmusic.TOGGLE_REPEAT"

        // Buffer configuration constants for better streaming
        private const val MIN_BUFFER_MS = 15_000L // 15 seconds
        private const val MAX_BUFFER_MS = 60_000L // 60 seconds
        private const val BUFFER_FOR_PLAYBACK_MS = 2_500L // 2.5 seconds
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000L // 5 seconds

        // Retry configuration
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1000L
    }
    
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentArtwork: Bitmap? = null
    private var mediaNotificationProvider: MediaNotificationProvider? = null
    
    // Binder for external access
    private val binder = Binder()
    
    /**
     * Binder class for external services to access player and session
     */
    inner class Binder : android.os.Binder() {
        val player: ExoPlayer
            get() = this@PlaybackService.player ?: throw IllegalStateException("Player not initialized")
        
        val mediaSession: MediaSession
            get() = this@PlaybackService.mediaSession ?: throw IllegalStateException("MediaSession not initialized")
    }
    
    override fun onBind(intent: Intent?): android.os.IBinder? {
        // For media browser clients, use the binder
        return if (intent?.action == "android.media.browse.MediaBrowserService") {
            binder
        } else {
            super.onBind(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🎵 PlaybackService created")
        createNotificationChannel()
        initializePlayer()
        setupNotificationProvider()
    }
    
    private fun initializePlayer() {
        // Build ExoPlayer with proper audio configuration and buffer settings
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true // Handle audio focus automatically
            )
            .setHandleAudioBecomingNoisy(true) // Pause on headphone disconnect
            .setWakeMode(C.WAKE_MODE_LOCAL) // Keep CPU awake during playback
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        MIN_BUFFER_MS.toInt(),
                        MAX_BUFFER_MS.toInt(),
                        BUFFER_FOR_PLAYBACK_MS.toInt(),
                        BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS.toInt()
                    )
                    .setBackBuffer(30_000, false)
                    .build()
            )
            .build()
            .apply {
                // Set up player listener for state changes
                addListener(createPlayerListener())
                
                // Initialize equalizer with audio session ID
                equalizerManager.init(audioSessionId)
            }

        // Create MediaSession for system integration with custom session commands
        mediaSession = player?.let { exoPlayer ->
            MediaSession.Builder(this, exoPlayer)
                .setCallback(MediaSessionCallback())
                .setCustomLayout(createCustomLayout())
                .build()
        }

        Log.d(TAG, "✅ Player initialized with custom buffer config")
    }
    
    private fun setupNotificationProvider() {
        mediaNotificationProvider = MediaNotificationProvider(this)
        setMediaNotificationProvider(mediaNotificationProvider!!)
        Log.d(TAG, "✅ Modern notification provider set up")
    }
    
    private fun createCustomLayout(): ImmutableList<CommandButton> {
        // Create custom command buttons for shuffle and repeat
        val shuffleButton = CommandButton.Builder()
            .setDisplayName("Shuffle")
            .setIconResId(R.drawable.ic_notification_shuffle)
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY))
            .build()
            
        val repeatButton = CommandButton.Builder()
            .setDisplayName("Repeat")
            .setIconResId(R.drawable.ic_notification_repeat)
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_REPEAT, Bundle.EMPTY))
            .build()
            
        return ImmutableList.of(shuffleButton, repeatButton)
    }
    
    private fun createPlayerListener() = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "📊 Playing: $isPlaying")
            updateNotification()
        }
        
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            Log.d(TAG, "🎵 Track changed: ${mediaItem?.mediaMetadata?.title}")
            loadArtwork(mediaItem?.mediaMetadata?.artworkUri?.toString())
            updateNotification()
        }
        
        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(TAG, "📊 State: ${playbackStateToString(playbackState)}")
            when (playbackState) {
                Player.STATE_READY -> {
                    updateNotification()
                    // Reset retry counter on successful playback
                    retryAttempts = 0
                    lastErrorCode = 0
                }
                Player.STATE_ENDED -> {
                    Log.d(TAG, "🔚 Playback ended")
                    retryAttempts = 0
                }
                Player.STATE_IDLE -> {
                    /* Player is idle */
                    retryAttempts = 0
                }
                Player.STATE_BUFFERING -> { /* Buffering in progress */ }
            }
        }
        
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            Log.d(TAG, "🔀 Shuffle mode: $shuffleModeEnabled")
            updateNotification()
        }
        
        override fun onRepeatModeChanged(repeatMode: Int) {
            Log.d(TAG, "🔁 Repeat mode: ${repeatModeToString(repeatMode)}")
            updateNotification()
        }
        
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "❌ Player error: ${error.message}", error)
            handlePlaybackError(error)
        }
    }
    
    private var retryAttempts = 0
    private var lastErrorCode = 0

    private fun handlePlaybackError(error: PlaybackException) {
        val p = player ?: return

        val isNetworkError = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE

        lastErrorCode = error.errorCode

        when {
            // HTTP errors (403/410) - URL likely expired, don't retry
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                Log.d(TAG, "❌ HTTP error (URL expired), skipping to next or stopping")
                retryAttempts = 0
                if (p.hasNextMediaItem()) {
                    p.seekToNext()
                    p.prepare()
                }
            }

            // Network errors with automatic retry
            isNetworkError && retryAttempts < MAX_RETRY_ATTEMPTS -> {
                retryAttempts++
                Log.d(TAG, "🔄 Network error, retrying ${retryAttempts}/$MAX_RETRY_ATTEMPTS...")
                serviceScope.launch {
                    delay(RETRY_DELAY_MS * retryAttempts) // Progressive delay
                    p.prepare()
                    p.play()
                }
            }

            // Max retries reached or other errors - skip to next if available
            p.hasNextMediaItem() -> {
                Log.d(TAG, "⏭️ Skipping to next due to error")
                retryAttempts = 0
                p.seekToNext()
                p.prepare()
            }

            else -> {
                Log.d(TAG, "⚠️ Playback error, no next item to skip to")
                retryAttempts = 0
            }
        }
    }
    
    private fun playbackStateToString(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN"
    }
    
    private fun repeatModeToString(mode: Int): String = when (mode) {
        Player.REPEAT_MODE_OFF -> "OFF"
        Player.REPEAT_MODE_ONE -> "ONE"
        Player.REPEAT_MODE_ALL -> "ALL"
        else -> "UNKNOWN"
    }
    
    private fun loadArtwork(url: String?) {
        if (url.isNullOrEmpty()) {
            currentArtwork = null
            return
        }
        
        serviceScope.launch {
            try {
                val loader = ImageLoader(this@PlaybackService)
                val request = ImageRequest.Builder(this@PlaybackService)
                    .data(url)
                    .allowHardware(false)
                    .size(512, 512)
                    .build()
                val result = loader.execute(request)
                currentArtwork = (result as? SuccessResult)?.drawable?.let { 
                    (it as? BitmapDrawable)?.bitmap 
                }
                updateNotification()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load artwork", e)
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> player?.play()
            ACTION_PAUSE -> player?.pause()
            ACTION_NEXT -> {
                player?.let { p ->
                    if (p.hasNextMediaItem()) p.seekToNext()
                }
            }
            ACTION_PREVIOUS -> {
                player?.let { p ->
                    if (p.currentPosition > 3000) {
                        p.seekTo(0)
                    } else if (p.hasPreviousMediaItem()) {
                        p.seekToPrevious()
                    }
                }
            }
            ACTION_TOGGLE_SHUFFLE -> {
                player?.let { p ->
                    p.shuffleModeEnabled = !p.shuffleModeEnabled
                }
            }
            ACTION_TOGGLE_REPEAT -> {
                player?.let { p ->
                    p.repeatMode = when (p.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }
            }
            ACTION_STOP -> {
                player?.stop()
                stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
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
            
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun updateNotification() {
        val notification = buildModernNotification()
        startForeground(NOTIFICATION_ID, notification)
    }
    
    private fun buildModernNotification(): Notification {
        val exoPlayer = player ?: return buildDefaultNotification()
        
        val mediaItem = exoPlayer.currentMediaItem
        val title = mediaItem?.mediaMetadata?.title?.toString() ?: "Unknown"
        val artist = mediaItem?.mediaMetadata?.artist?.toString() ?: "Unknown Artist"
        val album = mediaItem?.mediaMetadata?.albumTitle?.toString()
        
        val isPlaying = exoPlayer.isPlaying
        val hasNext = exoPlayer.hasNextMediaItem()
        val hasPrevious = exoPlayer.hasPreviousMediaItem() || exoPlayer.currentPosition > 3000
        
        // Content intent to open app
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Delete intent for swipe dismiss
        val deleteIntent = PendingIntent.getService(
            this, 100,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build modern styled notification
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText(album)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setLargeIcon(currentArtwork)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(deleteIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionCompatToken)
                    .setShowActionsInCompactView(1, 2, 3) // Prev, Play/Pause, Next
                    .setShowCancelButton(!isPlaying)
                    .setCancelButtonIntent(deleteIntent)
            )
        
        // Get current shuffle/repeat state
        val shuffleEnabled = exoPlayer.shuffleModeEnabled
        val repeatMode = exoPlayer.repeatMode
        
        // Add 5 actions: Shuffle, Previous, Play/Pause, Next, Repeat
        
        // Shuffle (index 0)
        builder.addAction(
            R.drawable.ic_notification_shuffle,
            if (shuffleEnabled) "Shuffle On" else "Shuffle Off",
            createActionIntent(ACTION_TOGGLE_SHUFFLE, 0)
        )
        
        // Previous (index 1)
        builder.addAction(
            R.drawable.ic_notification_prev,
            "Previous",
            createActionIntent(ACTION_PREVIOUS, 1)
        )
        
        // Play/Pause (index 2)
        builder.addAction(
            if (isPlaying) R.drawable.ic_notification_pause else R.drawable.ic_notification_play,
            if (isPlaying) "Pause" else "Play",
            if (isPlaying) createActionIntent(ACTION_PAUSE, 2) else createActionIntent(ACTION_PLAY, 2)
        )
        
        // Next (index 3)
        builder.addAction(
            R.drawable.ic_notification_next,
            "Next",
            createActionIntent(ACTION_NEXT, 3)
        )
        
        // Repeat (index 4)
        val repeatIcon = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> R.drawable.ic_notification_repeat_one
            Player.REPEAT_MODE_ALL -> R.drawable.ic_notification_repeat
            else -> R.drawable.ic_notification_repeat
        }
        builder.addAction(
            repeatIcon,
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> "Repeat One"
                Player.REPEAT_MODE_ALL -> "Repeat All"
                else -> "Repeat Off"
            },
            createActionIntent(ACTION_TOGGLE_REPEAT, 4)
        )
        
        // Enable colorized notification (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && currentArtwork != null) {
            builder.setColorized(true)
        }
        
        return builder.build()
    }
    
    private fun createActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, requestCode, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
    
    private fun buildDefaultNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SonicMusic")
            .setContentText("Ready to play")
            .setSmallIcon(R.drawable.ic_notification_small)
            .setOngoing(false)
            .build()
    }
    
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }
    
    override fun onDestroy() {
        Log.d(TAG, "🔚 PlaybackService destroyed")
        serviceScope.cancel()
        mediaNotificationProvider?.release()
        mediaSession?.run {
            player.release()
            release()
        }
        equalizerManager.release()
        player = null
        mediaSession = null
        super.onDestroy()
    }
    
    private inner class MediaSessionCallback : MediaSession.Callback {
        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            Log.d(TAG, "🔌 Controller connected: ${controller.packageName}")
        }
        
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
            when (customCommand.customAction) {
                ACTION_TOGGLE_SHUFFLE -> {
                    session.player.shuffleModeEnabled = !session.player.shuffleModeEnabled
                }
                ACTION_TOGGLE_REPEAT -> {
                    session.player.repeatMode = when (session.player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }
            }
            return com.google.common.util.concurrent.Futures.immediateFuture(
                androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
            )
        }
    }
}