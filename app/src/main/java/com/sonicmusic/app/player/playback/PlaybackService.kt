package com.sonicmusic.app.player.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.common.collect.ImmutableList
import com.sonicmusic.app.R
import com.sonicmusic.app.data.local.datastore.SettingsDataStore
import com.sonicmusic.app.domain.model.Song
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.player.audio.AudioEngine
import com.sonicmusic.app.player.notification.MediaNotificationProvider

import com.sonicmusic.app.presentation.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    @Inject
    lateinit var songRepository: SongRepository

    @Inject
    lateinit var historyRepository: com.sonicmusic.app.domain.repository.HistoryRepository

    companion object {
        private const val TAG = "SonicPlayback"
        private const val CHANNEL_ID = "sonic_music_playback"
        const val NOTIFICATION_ID = 1

        const val ACTION_PLAY = "com.sonicmusic.PLAY"
        const val ACTION_PAUSE = "com.sonicmusic.PAUSE"
        const val ACTION_NEXT = "com.sonicmusic.NEXT"
        const val ACTION_PREVIOUS = "com.sonicmusic.PREVIOUS"
        const val ACTION_STOP = "com.sonicmusic.STOP"
        const val ACTION_TOGGLE_SHUFFLE = "com.sonicmusic.TOGGLE_SHUFFLE"
        const val ACTION_TOGGLE_LIKE = "com.sonicmusic.TOGGLE_LIKE"
        const val ACTION_TOGGLE_REPEAT = "com.sonicmusic.TOGGLE_REPEAT"

        // Buffer configuration constants for better streaming
        private const val MIN_BUFFER_MS = 15_000L // 15 seconds
        private const val MAX_BUFFER_MS = 60_000L // 60 seconds
        private const val BUFFER_FOR_PLAYBACK_MS = 2_500L // 2.5 seconds
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000L // 5 seconds

        // Retry configuration
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1000L

        // Stream request headers (YouTube/CDN is stricter with generic agents on some devices)
        private const val YOUTUBE_STREAM_USER_AGENT =
            "com.google.android.youtube/19.09.37 (Linux; U; Android 14) gzip"
        private const val HTTP_CONNECT_TIMEOUT_MS = 20_000
        private const val HTTP_READ_TIMEOUT_MS = 20_000
    }
    
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentArtwork: Bitmap? = null
    private var mediaNotificationProvider: MediaNotificationProvider? = null
    private var skipSilenceJob: Job? = null
    private var gaplessPlaybackJob: Job? = null
    private var crossfadeSettingsJob: Job? = null
    private var crossfadeMonitorJob: Job? = null
    private var crossfadeTransitionJob: Job? = null
    private var crossfadeEnabled: Boolean = false
    private var crossfadeDurationMs: Long = 0L
    private var isCrossfadeTransitionRunning: Boolean = false
    
    // Telemetry tracking state
    private var lastHistoryMediaItem: MediaItem? = null
    private var currentPlaySessionStartTimeMs: Long = 0L
    private var accumulatedPlayDurationMs: Long = 0L
    
    // Bug 1 fix: Removed custom onBind/Binder that was intercepting Media3's
    // MediaController IPC channel. Let MediaSessionService.onBind() handle everything.

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d(TAG, "🟢 onCreate — PID:${android.os.Process.myPid()} TID:${Thread.currentThread().id}")
        initializePlayer()
        observePlaybackSettings()
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

            val mediaSourceFactory = buildMediaSourceFactory()

            player = ExoPlayer.Builder(this)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setMediaSourceFactory(mediaSourceFactory)
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

                // Defer audio engine init off main thread to reduce startup jank
                val sessionId = audioSessionId
                serviceScope.launch(Dispatchers.Default) {
                    audioEngine.initialize(sessionId)
                }
            }

        // Create MediaSession for system integration with custom session commands
        mediaSession = player?.let { exoPlayer ->
            MediaSession.Builder(this, exoPlayer)
                .setCallback(MediaSessionCallback())
                .setCustomLayout(updateCustomLayout(false, exoPlayer.repeatMode)) // Default to not liked initially
                .build()
        }

        Log.d(TAG, "✅ Player initialized with custom buffer config")
    }

    private fun buildMediaSourceFactory(): DefaultMediaSourceFactory {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(YOUTUBE_STREAM_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Referer" to "https://www.youtube.com/",
                ),
            )

        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        return DefaultMediaSourceFactory(dataSourceFactory)
    }
    
    private fun setupNotificationProvider() {
        mediaNotificationProvider = MediaNotificationProvider(this) { songId ->
            songRepository.isLiked(songId)
        }
        setMediaNotificationProvider(mediaNotificationProvider!!)
        Log.d(TAG, "✅ Modern notification provider set up")
    }

    private fun observePlaybackSettings() {
        skipSilenceJob?.cancel()
        gaplessPlaybackJob?.cancel()
        crossfadeSettingsJob?.cancel()

        skipSilenceJob = serviceScope.launch {
            settingsDataStore.skipSilence.collect { enabled ->
                player?.skipSilenceEnabled = enabled
                Log.d(TAG, "🔇 Skip silence: $enabled")
            }
        }

        gaplessPlaybackJob = serviceScope.launch {
            settingsDataStore.gaplessPlayback.collect { enabled ->
                player?.pauseAtEndOfMediaItems = !enabled
                Log.d(TAG, "🎚️ Gapless playback: $enabled")
            }
        }

        crossfadeSettingsJob = serviceScope.launch {
            settingsDataStore.crossfadeDuration.collect { seconds ->
                val safeSeconds = seconds.coerceIn(0, 12)
                crossfadeEnabled = safeSeconds > 0
                crossfadeDurationMs = if (crossfadeEnabled) {
                    (safeSeconds * 1000L).coerceIn(1000L, 12000L)
                } else {
                    0L
                }

                if (!crossfadeEnabled && !isCrossfadeTransitionRunning) {
                    player?.volume = 1f
                }
                Log.d(
                    TAG,
                    if (crossfadeEnabled) {
                        "🔀 Crossfade enabled: ${crossfadeDurationMs}ms"
                    } else {
                        "🔀 Crossfade disabled"
                    }
                )
            }
        }
    }

    private fun startCrossfadeMonitor() {
        if (crossfadeMonitorJob?.isActive == true) return

        crossfadeMonitorJob = serviceScope.launch {
            while (isActive) {
                val currentPlayer = player
                if (
                    currentPlayer != null &&
                    crossfadeEnabled &&
                    !isCrossfadeTransitionRunning &&
                    currentPlayer.isPlaying &&
                    currentPlayer.hasNextMediaItem()
                ) {
                    val duration = currentPlayer.duration
                    val position = currentPlayer.currentPosition

                    if (duration > 0 && duration != C.TIME_UNSET) {
                        val remaining = duration - position
                        if (remaining in 1..crossfadeDurationMs) {
                            runCrossfadeTransition(currentPlayer, crossfadeDurationMs)
                        }
                    }
                }

                delay(180)
            }
        }
    }

    private fun stopCrossfadeMonitor() {
        crossfadeMonitorJob?.cancel()
        crossfadeMonitorJob = null
        crossfadeTransitionJob?.cancel()
        crossfadeTransitionJob = null
        isCrossfadeTransitionRunning = false
        player?.volume = 1f
    }

    private fun runCrossfadeTransition(currentPlayer: ExoPlayer, durationMs: Long) {
        if (isCrossfadeTransitionRunning) return

        crossfadeTransitionJob?.cancel()
        crossfadeTransitionJob = serviceScope.launch {
            isCrossfadeTransitionRunning = true
            val halfDuration = (durationMs / 2L).coerceAtLeast(240L)
            val steps = 8
            val stepDelay = (halfDuration / steps).coerceAtLeast(16L)

            try {
                for (step in steps downTo 0) {
                    currentPlayer.volume = step.toFloat() / steps.toFloat()
                    delay(stepDelay)
                }

                if (currentPlayer.hasNextMediaItem()) {
                    currentPlayer.seekToNext()
                    if (!currentPlayer.isPlaying) {
                        currentPlayer.play()
                    }
                }

                for (step in 0..steps) {
                    currentPlayer.volume = step.toFloat() / steps.toFloat()
                    delay(stepDelay)
                }
            } finally {
                currentPlayer.volume = 1f
                isCrossfadeTransitionRunning = false
            }
        }
    }
    

    
    private fun createPlayerListener() = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val now = System.currentTimeMillis()
            if (isPlaying) {
                currentPlaySessionStartTimeMs = now
                startCrossfadeMonitor()
            } else {
                if (currentPlaySessionStartTimeMs > 0) {
                    accumulatedPlayDurationMs += (now - currentPlaySessionStartTimeMs)
                    currentPlaySessionStartTimeMs = 0
                }
                stopCrossfadeMonitor()
            }
        }
        
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            Log.d(TAG, "🎵 Track changed: ${mediaItem?.mediaMetadata?.title}")
            if (!isCrossfadeTransitionRunning) {
                player?.volume = 1f
            }
            
            // ════════ TELEMETRY COLLECTION ════════
            val now = System.currentTimeMillis()
            if (player?.isPlaying == true && currentPlaySessionStartTimeMs > 0) {
                accumulatedPlayDurationMs += (now - currentPlaySessionStartTimeMs)
                currentPlaySessionStartTimeMs = now
            }

            lastHistoryMediaItem?.let { prevItem ->
                val prevSongId = prevItem.mediaId
                if (prevSongId.isNotBlank() && accumulatedPlayDurationMs > 0) {
                    val playDurationSecs = (accumulatedPlayDurationMs / 1000).toInt().coerceAtLeast(0)
                    
                    // We need the total duration of the previous item.
                    // If the user skipped, we might not have it in the ExoPlayer state accurately right now,
                    // but we can extract it from the queue/metadata if available.
                    val durationMs = prevItem.mediaMetadata.extras?.getLong("duration_ms") ?: 0L
                    val totalDurationSecs = (durationMs / 1000).toInt()
                    
                    val completed = totalDurationSecs > 0 && 
                            playDurationSecs.toFloat() / totalDurationSecs >= 0.8f
                    
                    // Construct song object from MediaItem for history
                    val song = Song(
                        id = prevSongId,
                        title = prevItem.mediaMetadata.title?.toString() ?: "Unknown",
                        artist = prevItem.mediaMetadata.artist?.toString() ?: "Unknown",
                        album = prevItem.mediaMetadata.albumTitle?.toString(),
                        duration = totalDurationSecs,
                        thumbnailUrl = prevItem.mediaMetadata.artworkUri?.toString() ?: ""
                    )

                    serviceScope.launch {
                        try {
                            historyRepository.recordPlayback(
                                song,
                                playDuration = playDurationSecs,
                                completed = completed,
                                totalDuration = totalDurationSecs
                            )
                            Log.d(TAG, "📊 Telemetry recorded: ${song.title} | Dur: ${playDurationSecs}s | Completed: $completed")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Failed to record telemetry", e)
                        }
                    }
                }
            }

            // Reset for the new track
            lastHistoryMediaItem = mediaItem
            accumulatedPlayDurationMs = 0L
            if (player?.isPlaying == true) {
                currentPlaySessionStartTimeMs = System.currentTimeMillis()
            } else {
                currentPlaySessionStartTimeMs = 0L
            }
            // ══════════════════════════════════════

            // Update custom layout for the new track
            mediaItem?.mediaId?.let { songId ->
                serviceScope.launch {
                    val isLiked = withContext(Dispatchers.IO) {
                        runCatching { songRepository.isLiked(songId) }.getOrDefault(false)
                    }
                    val currentRepeatMode = player?.repeatMode ?: Player.REPEAT_MODE_OFF
                    mediaSession?.setCustomLayout(updateCustomLayout(isLiked, currentRepeatMode))
                }
            }
        }
        
        override fun onPlaybackStateChanged(playbackState: Int) {
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
        
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { }
        
        override fun onRepeatModeChanged(repeatMode: Int) {
            // Update custom layout to reflect new repeat mode
            val currentPlayer = player ?: return
            val currentMediaItem = currentPlayer.currentMediaItem
            val songId = currentMediaItem?.mediaId.orEmpty()
            
            serviceScope.launch {
                val isLiked = if (songId.isNotBlank()) {
                     withContext(Dispatchers.IO) {
                        runCatching { songRepository.isLiked(songId) }.getOrDefault(false)
                    }
                } else false
                
                mediaSession?.setCustomLayout(updateCustomLayout(isLiked, repeatMode))
                mediaSession?.let { session ->
                    mediaNotificationProvider?.refreshNotification(session)
                }
            }
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

        if (isNetworkError && !isNetworkAvailable()) {
            Log.d(TAG, "📴 Network unavailable, skipping auto-retry")
            retryAttempts = 0
            return
        }

        when {
            // HTTP errors (403/410) - URL likely expired, don't retry
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                Log.d(TAG, "❌ HTTP error (likely expired URL), waiting for URL refresh callback")
                retryAttempts = 0
                p.pause()
            }

            // Corrupt/missing stream URL cases should also refresh the current track URL.
            error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> {
                Log.d(TAG, "❌ Stream URL invalid/missing, waiting for URL refresh callback")
                retryAttempts = 0
                p.pause()
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

    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            false
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
        android.util.Log.d(TAG, "🔵 onStartCommand — action=${intent?.action}, startId=$startId, flags=$flags")
        
        // Let MediaSessionService handle media button intents first
        super.onStartCommand(intent, flags, startId)
        
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
            ACTION_TOGGLE_LIKE -> {
                toggleLikeForCurrentSong()
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
        // Bug 4 fix: START_STICKY keeps the service alive when the app is swiped from recents
        return android.app.Service.START_STICKY
    }
    

    
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        android.util.Log.d(TAG, "🟡 onTaskRemoved — isPlaying=${player?.isPlaying}")
        // Keep playback alive if currently playing
        if (player?.isPlaying != true) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        android.util.Log.d(TAG, "🔴 onDestroy")
        skipSilenceJob?.cancel()
        gaplessPlaybackJob?.cancel()
        crossfadeSettingsJob?.cancel()
        stopCrossfadeMonitor()
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
                .add(SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY))
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
                ACTION_TOGGLE_LIKE -> {
                    toggleLikeForCurrentSong()
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

    private fun toggleLikeForCurrentSong() {
        val currentPlayer = player ?: return
        val mediaItem = currentPlayer.currentMediaItem ?: return
        val songId = mediaItem.mediaId
        if (songId.isBlank()) return

        val metadata = mediaItem.mediaMetadata
        val durationSeconds = currentPlayer.duration
            .takeIf { it > 0L && it != C.TIME_UNSET }
            ?.div(1000L)
            ?.toInt()
            ?: 0

        val song = Song(
            id = songId,
            title = metadata.title?.toString() ?: "Unknown",
            artist = metadata.artist?.toString() ?: "Unknown Artist",
            album = metadata.albumTitle?.toString(),
            duration = durationSeconds,
            thumbnailUrl = metadata.artworkUri?.toString() ?: ""
        )

        serviceScope.launch {
            val wasLiked = withContext(Dispatchers.IO) {
                runCatching { songRepository.isLiked(songId) }.getOrDefault(false)
            }
            val shouldLike = !wasLiked

            // Update MediaSession custom layout for System Media Player integration
            mediaSession?.setCustomLayout(updateCustomLayout(shouldLike, player?.repeatMode ?: Player.REPEAT_MODE_OFF))

            // Optimistic update for notification shade
            mediaSession?.let { session ->
                mediaNotificationProvider?.setLikedStateOverride(songId, shouldLike)
                mediaNotificationProvider?.refreshNotification(session)
            }

            val saveResult = withContext(Dispatchers.IO) {
                runCatching {
                    kotlinx.coroutines.withTimeout(2000L) {
                        if (shouldLike) {
                            songRepository.likeSong(song)
                        } else {
                            songRepository.unlikeSong(songId)
                        }
                    }
                }
            }

            saveResult.onSuccess {
                mediaNotificationProvider?.clearLikedStateOverride(songId)
                mediaSession?.let { session ->
                    mediaNotificationProvider?.refreshNotification(session)
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to toggle like from notification", error)
                // Revert UI on failure
                mediaSession?.setCustomLayout(updateCustomLayout(wasLiked, player?.repeatMode ?: Player.REPEAT_MODE_OFF))
                mediaNotificationProvider?.setLikedStateOverride(songId, wasLiked)
                mediaSession?.let { session ->
                    mediaNotificationProvider?.refreshNotification(session)
                }
            }
        }
    }

    private fun updateCustomLayout(isLiked: Boolean, repeatMode: Int): ImmutableList<CommandButton> {
        val likeButton = CommandButton.Builder()
            .setDisplayName(if (isLiked) "Unlike" else "Like")
            .setIconResId(if (isLiked) R.drawable.ic_notification_like else R.drawable.ic_notification_like_outline)
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY))
            .build()
            
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
        
        val repeatButton = CommandButton.Builder()
            .setDisplayName(repeatLabel)
            .setIconResId(repeatIcon)
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_REPEAT, Bundle.EMPTY))
            .build()
            
        return ImmutableList.of(likeButton, repeatButton)
    }
}
