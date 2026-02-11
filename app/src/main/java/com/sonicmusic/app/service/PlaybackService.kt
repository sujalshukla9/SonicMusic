package com.sonicmusic.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.media3.exoplayer.DefaultRenderersFactory
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
    lateinit var audioEngine: AudioEngine

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
        initializePlayer()
        setupNotificationProvider()
    }
    
    private fun initializePlayer() {
        // Build ExoPlayer with proper audio configuration and buffer settings
            // Apple Music-style Audio Attributes
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
                .build()

            player = ExoPlayer.Builder(this)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setRenderersFactory(
                    DefaultRenderersFactory(this)
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                )
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
                

                
                // Initialize premium audio engine
                audioEngine.initialize(audioSessionId)
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
        }
        
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            Log.d(TAG, "🎵 Track changed: ${mediaItem?.mediaMetadata?.title}")
        }
        
        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(TAG, "📊 State: ${playbackStateToString(playbackState)}")
            when (playbackState) {
                Player.STATE_READY -> {
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
        }
        
        override fun onRepeatModeChanged(repeatMode: Int) {
            Log.d(TAG, "🔁 Repeat mode: ${repeatModeToString(repeatMode)}")
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

        audioEngine.release()
        player = null
        mediaSession = null
        super.onDestroy()
    }
    
    private inner class MediaSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            val sessionCommands = connectionResult.availableSessionCommands
                .buildUpon()
                .add(SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_TOGGLE_REPEAT, Bundle.EMPTY))
                .build()
            
            return MediaSession.ConnectionResult.accept(
                sessionCommands,
                connectionResult.availablePlayerCommands
            )
        }

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