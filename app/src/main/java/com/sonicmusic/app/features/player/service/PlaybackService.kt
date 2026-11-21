package com.sonicmusic.app.features.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory

import android.net.Uri
import java.io.File
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
import com.sonicmusic.app.domain.model.StreamQuality
import com.sonicmusic.app.domain.repository.SongRepository
import com.sonicmusic.app.features.player.audio.PlaybackGain
import com.sonicmusic.app.features.player.audio.AudioEngine
import com.sonicmusic.app.features.player.audio.PlaybackDiagnostics
import com.sonicmusic.app.features.player.audio.PlaybackStatus
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.DecoderReuseEvaluation

import com.sonicmusic.app.features.player.notification.MediaNotificationProvider

import com.sonicmusic.app.app.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    lateinit var playbackDiagnostics: PlaybackDiagnostics

    @Inject
    lateinit var playerServiceConnection: dagger.Lazy<PlayerServiceConnection>



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
            "com.google.android.youtube/21.23.483 (Linux; U; Android 14; en_US) gzip"
        private const val HTTP_CONNECT_TIMEOUT_MS = 20_000
        private const val HTTP_READ_TIMEOUT_MS = 20_000
    }
    
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var player1: ExoPlayer? = null
    private var player2: ExoPlayer? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentArtwork: Bitmap? = null
    private var mediaNotificationProvider: MediaNotificationProvider? = null
    private var skipSilenceJob: Job? = null
    private var transitionSettingsJob: Job? = null
    private var spatialAudioSettingsJob: Job? = null
    private var soundCheckSettingsJob: Job? = null
    private var crossfadeMonitorJob: Job? = null
    private var crossfadeEngine: CrossfadeEngine? = null
    private val embeddedTrackLoudness = android.util.LruCache<Pair<String, String>, Double>(32)
    
    // Telemetry tracking state
    private var lastHistoryMediaItem: MediaItem? = null
    private var currentPlaySessionStartTimeMs: Long = 0L
    private var accumulatedPlayDurationMs: Long = 0L
    
    // Bug 1 fix: Removed custom onBind/Binder that was intercepting Media3's
    // MediaController IPC channel. Let MediaSessionService.onBind() handle everything.

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d(TAG, "onCreate — PID:${android.os.Process.myPid()} TID:${Thread.currentThread().id}")
        initializePlayer()
        observePlaybackSettings()
        setupNotificationProvider()
    }

    
    private fun initializePlayer() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val sharedAudioSessionId = audioManager.generateAudioSessionId()

        val audioAttributes = musicAudioAttributes(audioEngine.audioEngineState.value.spatialAudioEnabled)

        val mediaSourceFactory = buildMediaSourceFactory()

        val p1 = createExoPlayerInstance(audioAttributes, mediaSourceFactory)
        val p2 = createExoPlayerInstance(audioAttributes, mediaSourceFactory)

        p1.setAudioSessionId(sharedAudioSessionId)
        p1.addListener(createPlayerListener(p1))
        p1.addAnalyticsListener(createAnalyticsListener(p1))
        player1 = p1

        p2.setAudioSessionId(sharedAudioSessionId)
        p2.addListener(createPlayerListener(p2))
        p2.addAnalyticsListener(createAnalyticsListener(p2))
        player2 = p2

        player = player1

        // Defer audio engine init off main thread to reduce startup jank
        serviceScope.launch(Dispatchers.Default) {
            audioEngine.initialize(sharedAudioSessionId)
        }

        // Create MediaSession for system integration with custom session commands
        mediaSession = createSession(p1)

        crossfadeEngine = CrossfadeEngine(
            scope = serviceScope,
            player1 = p1,
            player2 = p2,
            mediaSessionProvider = { mediaSession },
            gainForItem = ::gainForItem,
            onPlayerSwapped = { newActivePlayer ->
                player = newActivePlayer
                updateSpatialPlayback(newActivePlayer)
                Log.d(TAG, "Active player swapped by CrossfadeEngine to: ${if (newActivePlayer == player1) "player1" else "player2"}")
            }
        )

        Log.d(TAG, "Dual players and CrossfadeEngine initialized with shared audioSessionId: $sharedAudioSessionId")
    }

    private fun createSession(exoPlayer: ExoPlayer): MediaSession =
        MediaSession.Builder(this, exoPlayer)
            .setCallback(MediaSessionCallback())
            .setCustomLayout(updateCustomLayout(false, exoPlayer.repeatMode))
            .build()

    private fun releaseSession() {
        val session = mediaSession ?: return
        mediaSession = null
        removeSession(session)
        session.release()
    }

    private fun musicAudioAttributes(spatialEnabled: Boolean) = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
        .setSpatializationBehavior(
            if (spatialEnabled) C.SPATIALIZATION_BEHAVIOR_AUTO else C.SPATIALIZATION_BEHAVIOR_NEVER
        )
        .build()

    private fun updateSpatialPlayback(current: ExoPlayer) {
        audioEngine.updateSpatialPlayback(current.audioAttributes.audioAttributesV21.audioAttributes, current.audioFormat)
    }

    private fun gainForItem(item: MediaItem?): Float {
        if (!audioEngine.audioEngineState.value.soundCheckEnabled || item == null) return 1f
        val uri = item.localConfiguration?.uri?.toString() ?: return 1f
        val loudnessDb = playbackDiagnostics.resolvedStreamInfo(item.mediaId, uri)?.loudnessDb
            ?: embeddedTrackLoudness.get(item.mediaId to uri)
        return PlaybackGain.normalization(loudnessDb)
    }

    private fun updateTrackGain(current: ExoPlayer) {
        if (crossfadeEngine?.isCrossfading != true) {
            current.volume = gainForItem(current.currentMediaItem)
        }
    }

    private fun createExoPlayerInstance(
        audioAttributes: AudioAttributes,
        mediaSourceFactory: androidx.media3.exoplayer.source.MediaSource.Factory
    ): ExoPlayer {
        return ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(
                object : DefaultRenderersFactory(this@PlaybackService) {
                    init {
                        setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
                        setEnableAudioFloatOutput(true)
                    }
                    override fun buildAudioSink(
                        context: Context,
                        enableFloatOutput: Boolean,
                        enableAudioTrackPlaybackParams: Boolean
                    ): androidx.media3.exoplayer.audio.AudioSink {
                        return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                            .setEnableFloatOutput(enableFloatOutput)
                            .build()
                    }
                }
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
                // The queue already contains the shuffled order presented to the user.
                setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.UnshuffledShuffleOrder(0))
                // Muxed fallback streams still only need their audio track decoded.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build()
            }
    }

    private fun buildMediaSourceFactory(): DefaultMediaSourceFactory {
        val baseHttpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(YOUTUBE_STREAM_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.9",
                ),
            )

        val httpDataSourceFactory = DynamicUserAgentHttpDataSourceFactory(baseHttpDataSourceFactory)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        return DefaultMediaSourceFactory(dataSourceFactory)
    }
    
    private fun setupNotificationProvider() {
        mediaNotificationProvider = MediaNotificationProvider(this) { songId ->
            songRepository.isLiked(songId)
        }
        setMediaNotificationProvider(mediaNotificationProvider!!)
        Log.d(TAG, "Modern notification provider set up")
    }

    private fun observePlaybackSettings() {
        skipSilenceJob?.cancel()
        transitionSettingsJob?.cancel()
        spatialAudioSettingsJob?.cancel()
        soundCheckSettingsJob?.cancel()

        skipSilenceJob = serviceScope.launch {
            settingsDataStore.skipSilence.collect { enabled ->
                player1?.skipSilenceEnabled = enabled
                player2?.skipSilenceEnabled = enabled
                Log.d(TAG, "Skip silence: $enabled")
            }
        }

        transitionSettingsJob = serviceScope.launch {
            settingsDataStore.gaplessPlayback.combine(settingsDataStore.crossfadeDuration, ::Pair)
                .distinctUntilChanged()
                .collect { (gapless, seconds) ->
                    val durationMs = seconds.coerceIn(0, 12) * 1000L
                    val preloadEnabled = gapless && durationMs == 0L
                    val preload = if (preloadEnabled) {
                        ExoPlayer.PreloadConfiguration(3_000_000L)
                    } else {
                        // Crossfade already preloads with the standby player.
                        ExoPlayer.PreloadConfiguration.DEFAULT
                    }
                    player1?.setPreloadConfiguration(preload)
                    player2?.setPreloadConfiguration(preload)
                    crossfadeEngine?.setDurationMs(durationMs)

                    if (durationMs == 0L) {
                        stopCrossfadeMonitor()
                        player?.let(::updateTrackGain)
                    } else if (player?.isPlaying == true) {
                        startCrossfadeMonitor()
                    }

                    Log.d(TAG, "Track transitions: gapless preload=$preloadEnabled, crossfade=${durationMs}ms")
                }
        }

        soundCheckSettingsJob = serviceScope.launch {
            audioEngine.audioEngineState
                .map { it.soundCheckEnabled }
                .distinctUntilChanged()
                .collect { player?.let(::updateTrackGain) }
        }

        spatialAudioSettingsJob = serviceScope.launch {
            audioEngine.audioEngineState
                .map { it.spatialAudioEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    val attributes = musicAudioAttributes(enabled)
                    player1?.setAudioAttributes(attributes, true)
                    player2?.setAudioAttributes(attributes, true)
                    player?.let(::updateSpatialPlayback)
                }
        }
    }

    private fun startCrossfadeMonitor() {
        val engine = crossfadeEngine ?: return
        if (!engine.isEnabled) {
            stopCrossfadeMonitor()
            return
        }
        val currentPlayer = player ?: return
        if (!currentPlayer.isPlaying) {
            return
        }

        crossfadeMonitorJob?.cancel()
        crossfadeMonitorJob = serviceScope.launch(Dispatchers.Main) {
            while (isActive) {
                val active = player
                val cEngine = crossfadeEngine
                if (active == null || cEngine == null || !cEngine.isEnabled || !active.isPlaying) {
                    break
                }

                val duration = active.duration
                val position = active.currentPosition

                if (duration > 0L && duration != C.TIME_UNSET) {
                    cEngine.onPositionUpdate(position, duration)

                    val remaining = duration - position
                    val nextDelay = when {
                        remaining > 30_000L -> 5_000L
                        remaining > 15_000L -> 2_000L
                        remaining > 6_000L -> 500L
                        else -> 150L
                    }
                    delay(nextDelay)
                } else {
                    delay(1000L)
                }
            }
        }
    }

    private fun stopCrossfadeMonitor() {
        crossfadeMonitorJob?.cancel()
        crossfadeMonitorJob = null
    }
    

    
    private fun createPlayerListener(targetPlayer: ExoPlayer) = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (targetPlayer != player) return
            Log.d(TAG, "Audio session ID changed: $audioSessionId")
            serviceScope.launch(Dispatchers.Default) {
                audioEngine.initialize(audioSessionId)
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (targetPlayer != player) return
            if (targetPlayer.playbackState == Player.STATE_ENDED) {
                val nextIndex = targetPlayer.currentMediaItemIndex + 1
                if (nextIndex < targetPlayer.mediaItemCount) {
                    Log.d(TAG, "Service onTimelineChanged: Auto-resuming ended playback at index $nextIndex (count=${targetPlayer.mediaItemCount})")
                    targetPlayer.seekTo(nextIndex, 0L)
                    targetPlayer.prepare()
                    targetPlayer.play()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (targetPlayer != player) return
            updateSpatialPlayback(targetPlayer)
            val now = System.currentTimeMillis()
            if (isPlaying) {
                currentPlaySessionStartTimeMs = now
                playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.PLAYING)
                startCrossfadeMonitor()
            } else {
                if (currentPlaySessionStartTimeMs > 0) {
                    accumulatedPlayDurationMs += (now - currentPlaySessionStartTimeMs)
                    currentPlaySessionStartTimeMs = 0
                }
                if (targetPlayer.playbackState == Player.STATE_BUFFERING) {
                    playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.BUFFERING)
                } else {
                    playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.IDLE)
                }
                stopCrossfadeMonitor()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && targetPlayer == player && crossfadeEngine?.isCrossfading == true) {
                crossfadeEngine?.cancelCrossfade()
            }
        }
        
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (targetPlayer != player) return
            Log.d(TAG, "Track changed: ${mediaItem?.mediaMetadata?.title}")

            updateTrackGain(targetPlayer)
            
            // ════════ TELEMETRY COLLECTION ════════
            val now = System.currentTimeMillis()
            if (targetPlayer.isPlaying && currentPlaySessionStartTimeMs > 0) {
                accumulatedPlayDurationMs += (now - currentPlaySessionStartTimeMs)
                currentPlaySessionStartTimeMs = now
            }

            lastHistoryMediaItem?.let { prevItem ->
                val prevSongId = prevItem.mediaId
                if (prevSongId.isNotBlank() && accumulatedPlayDurationMs > 0) {
                    val playDurationSecs = (accumulatedPlayDurationMs / 1000).toInt().coerceAtLeast(0)
                    val durationMs = prevItem.mediaMetadata.extras?.getLong("duration_ms") ?: 0L
                    val totalDurationSecs = (durationMs / 1000).toInt()
                    
                    val completed = totalDurationSecs > 0 && 
                            playDurationSecs.toFloat() / totalDurationSecs >= 0.8f

                    if (playDurationSecs >= 1 || completed) {
                        val song = Song(
                            id = prevSongId,
                            title = prevItem.mediaMetadata.title?.toString() ?: "Unknown",
                            artist = prevItem.mediaMetadata.artist?.toString() ?: "Unknown",
                            album = prevItem.mediaMetadata.albumTitle?.toString(),
                            duration = totalDurationSecs,
                            thumbnailUrl = prevItem.mediaMetadata.artworkUri?.toString() ?: ""
                        )

                        serviceScope.launch(Dispatchers.IO) {
                            try {
                                historyRepository.recordPlayback(
                                    song,
                                    playDuration = playDurationSecs,
                                    completed = completed,
                                    totalDuration = totalDurationSecs
                                )
                                Log.d(TAG, "Telemetry recorded: ${song.title} | Dur: ${playDurationSecs}s | Completed: $completed")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to record telemetry", e)
                            }
                        }
                    }
                }
            }

            // Reset for the new track
            lastHistoryMediaItem = mediaItem
            accumulatedPlayDurationMs = 0L
            if (targetPlayer.isPlaying) {
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
                    val currentRepeatMode = targetPlayer.repeatMode
                    mediaSession?.setCustomLayout(updateCustomLayout(isLiked, currentRepeatMode))
                }
            }

            if (targetPlayer.isPlaying) {
                startCrossfadeMonitor()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (targetPlayer != player) return
            updateSpatialPlayback(targetPlayer)
            updateTrackGain(targetPlayer)
            val format = targetPlayer.audioFormat ?: return
            val mediaId = targetPlayer.currentMediaItem?.mediaId
            Log.d(TAG, "ExoPlayer tracks changed: song=$mediaId, mime=${format.sampleMimeType}, sr=${format.sampleRate}, ch=${format.channelCount}, br=${format.bitrate}")
            playbackDiagnostics.onPlayingFormatChanged(format)
            audioEngine.updateFromExoPlayerFormat(format, mediaId)
        }
        
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (targetPlayer != player) return
            when (playbackState) {
                Player.STATE_READY -> {
                    // Reset retry counter on successful playback
                    retryAttempts = 0
                    lastErrorCode = 0
                    if (targetPlayer.isPlaying) {
                        playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.PLAYING)
                        startCrossfadeMonitor()
                    } else {
                        playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.IDLE)
                    }
                }
                Player.STATE_ENDED -> {
                    Log.d(TAG, "Playback ended")
                    retryAttempts = 0
                    stopCrossfadeMonitor()
                    playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.IDLE)
                }
                Player.STATE_IDLE -> {
                    /* Player is idle */
                    retryAttempts = 0
                    stopCrossfadeMonitor()
                    playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.IDLE)
                }
                Player.STATE_BUFFERING -> {
                    playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.BUFFERING)
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (targetPlayer != player) return
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                crossfadeEngine?.onUserSeek()
            }
            if (targetPlayer.isPlaying) {
                startCrossfadeMonitor()
            }
        }
        
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { }
        
        override fun onRepeatModeChanged(repeatMode: Int) {
            if (targetPlayer != player) return
            // Update custom layout to reflect new repeat mode
            val currentMediaItem = targetPlayer.currentMediaItem
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
            if (targetPlayer != player) return
            Log.e(TAG, "Player error: ${error.message}", error)
            playbackDiagnostics.onPlaybackStatusChanged(PlaybackStatus.ERROR, error.message)
            handlePlaybackError(error)
        }
    }

    private fun createAnalyticsListener(targetPlayer: ExoPlayer): AnalyticsListener {
        return object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                if (targetPlayer == player) {
                    Log.d(TAG, "Audio decoder initialized: $decoderName")
                    playbackDiagnostics.onDecoderInitialized(decoderName)
                }
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: androidx.media3.common.Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                val windowIndex = eventTime.windowIndex
                if (windowIndex !in 0 until eventTime.timeline.windowCount) return
                val item = eventTime.timeline.getWindow(windowIndex, androidx.media3.common.Timeline.Window()).mediaItem
                val uri = item.localConfiguration?.uri?.toString().orEmpty()
                if (uri.isNotEmpty()) {
                    PlaybackGain.replayGainLoudnessDb(format.metadata)?.let { loudnessDb ->
                        embeddedTrackLoudness.put(item.mediaId to uri, loudnessDb)
                    }
                }
                val current = targetPlayer.currentMediaItem
                if (targetPlayer == player && current?.mediaId == item.mediaId &&
                    current.localConfiguration?.uri == item.localConfiguration?.uri
                ) {
                    updateTrackGain(targetPlayer)
                    playbackDiagnostics.onRendererFormat(item.mediaId, uri, format)
                    audioEngine.updateFromExoPlayerFormat(format, item.mediaId)
                }
            }
        }
    }
    
    private var retryAttempts = 0
    private var lastErrorCode = 0

    private fun handlePlaybackError(error: PlaybackException) {
        val p = player ?: return

        val isNetworkError = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            error.errorCode == PlaybackException.ERROR_CODE_UNSPECIFIED

        lastErrorCode = error.errorCode

        if (isNetworkError && !isNetworkAvailable()) {
            val mediaItem = p.currentMediaItem
            if (mediaItem != null) {
                val videoId = mediaItem.mediaId
                val currentPos = p.currentPosition
                serviceScope.launch {
                    val title = mediaItem.mediaMetadata.title?.toString()
                    val artist = mediaItem.mediaMetadata.artist?.toString()
                    val source = songRepository.getSongStreamSource(videoId, title, artist)
                    if (source is com.sonicmusic.app.data.repository.SongSource.Local) {
                        Log.d(TAG, "Internet dropped. Found offline file, performing seamless fallback: ${source.filePath}")
                        val fileUri = normalizeSourceUri(source.filePath)
                        val updatedMediaItem = mediaItem.buildUpon()
                            .setUri(fileUri)
                            .build()
                        val index = p.currentMediaItemIndex
                        p.replaceMediaItem(index, updatedMediaItem)
                        p.seekTo(index, currentPos)
                        p.prepare()
                        p.play()
                        return@launch
                    } else {
                        Log.w(TAG, "Internet dropped and song is not available offline. Pausing playback gracefully.")
                        p.pause()
                    }
                }
            } else {
                Log.d(TAG, "Network unavailable, skipping auto-retry")
                retryAttempts = 0
            }
            return
        }

        val isExpiredUrlError = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            error.errorCode == PlaybackException.ERROR_CODE_UNSPECIFIED

        val currentMediaItem = p.currentMediaItem
        val failedUri = currentMediaItem?.localConfiguration?.uri?.toString()
        if (currentMediaItem != null && !failedUri.isNullOrBlank() && (isExpiredUrlError || isNetworkError)) {
            val songId = currentMediaItem.mediaId
            val currentPos = p.currentPosition
            serviceScope.launch {
                Log.w(TAG, "Playback error for $songId. Attempting automatic candidate fallback.")
                val fallbackResult = songRepository.getStreamWithInfo(
                    songId = songId,
                    quality = StreamQuality.BEST,
                    forceRefresh = true,
                    excludeUrls = setOf(failedUri),
                    title = currentMediaItem.mediaMetadata.title?.toString(),
                    artist = currentMediaItem.mediaMetadata.artist?.toString()
                )
                fallbackResult.onSuccess { (newUrl, newInfo) ->
                    if (p.currentMediaItem?.mediaId == songId &&
                        p.currentMediaItem?.localConfiguration?.uri?.toString() == failedUri &&
                        newUrl != failedUri && newUrl.isNotBlank()
                    ) {
                        Log.d(TAG, "Recovered from playback error for $songId.")
                        val updatedMediaItem = currentMediaItem.buildUpon()
                            .setUri(normalizeSourceUri(newUrl))
                            .build()
                        val index = p.currentMediaItemIndex
                        p.replaceMediaItem(index, updatedMediaItem)
                        p.seekTo(index, currentPos)
                        p.prepare()
                        p.play()
                        audioEngine.updateStreamInfo(newInfo, songId)
                        playbackDiagnostics.onCandidateSelected(songId, newInfo)
                        return@launch
                    }
                }
            }
        }

        when {
            // Expired stream URL errors - URL likely expired, pause and wait for URL refresh callback
            isExpiredUrlError -> {
                Log.d(TAG, "Expired stream URL error (${error.errorCode}), waiting for URL refresh callback")
                retryAttempts = 0
                p.pause()
            }

            // Network errors with automatic retry
            isNetworkError && retryAttempts < MAX_RETRY_ATTEMPTS -> {
                retryAttempts++
                Log.d(TAG, "Network error, retrying ${retryAttempts}/$MAX_RETRY_ATTEMPTS...")
                serviceScope.launch {
                    delay(RETRY_DELAY_MS * retryAttempts) // Progressive delay
                    p.prepare()
                    p.play()
                }
            }

            // Max retries reached or other errors - skip to next if available
            p.hasNextMediaItem() -> {
                Log.d(TAG, "Skipping to next due to error")
                retryAttempts = 0
                p.seekToNext()
                p.prepare()
            }

            else -> {
                Log.d(TAG, "Playback error, no next item to skip to")
                retryAttempts = 0
            }
        }
    }

    private fun normalizeSourceUri(source: String): Uri {
        val parsed = Uri.parse(source)
        return if (parsed.scheme.isNullOrBlank() && source.startsWith("/")) {
            try {
                androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "$packageName.provider",
                    File(source)
                )
            } catch (e: IllegalArgumentException) {
                Uri.fromFile(File(source))
            }
        } else {
            parsed
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
        android.util.Log.d(TAG, "onStartCommand — action=${intent?.action}, startId=$startId, flags=$flags")
        
        // Let MediaSessionService handle media button intents first
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            ACTION_PLAY -> {
                crossfadeEngine?.onUserResume() ?: player?.play()
            }
            ACTION_PAUSE -> {
                crossfadeEngine?.onUserPause() ?: player?.pause()
            }
            ACTION_NEXT -> {
                val handled = crossfadeEngine?.onUserSkip() ?: false
                if (!handled) {
                    player?.let { p ->
                        if (p.hasNextMediaItem()) p.seekToNext()
                    }
                }
            }
            ACTION_PREVIOUS -> {
                val handled = crossfadeEngine?.onUserPrevious() ?: false
                if (!handled) {
                    player?.let { p ->
                        if (p.currentPosition > 3000) {
                            p.seekTo(0)
                        } else if (p.hasPreviousMediaItem()) {
                            p.seekToPrevious()
                        }
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
        return android.app.Service.START_NOT_STICKY
    }
    

    
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession ?: player?.let { createSession(it).also { session -> mediaSession = session } }
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed, ending playback")
        // A bound controller can delay onDestroy. Stop the current audio and
        // cancel pending playback here, including the second crossfade player.
        playerServiceConnection.get().disconnect()
        stopCrossfadeMonitor()
        crossfadeEngine?.cancelCrossfade()
        player1?.pause()
        player2?.pause()
        releaseSession()
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        android.util.Log.d(TAG, "onDestroy")
        skipSilenceJob?.cancel()
        transitionSettingsJob?.cancel()
        stopCrossfadeMonitor()
        crossfadeEngine?.release()
        crossfadeEngine = null
        serviceScope.cancel()
        mediaNotificationProvider?.release()
        releaseSession()
        player1?.stop()
        player2?.stop()
        player1?.release()
        player2?.release()
        player1 = null
        player2 = null
        player = null
        audioEngine.release()
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
            Log.d(TAG, "Controller connected: ${controller.packageName}")
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

    internal fun toggleLikeForCurrentSong() {
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
            .setDisplayName(if (isLiked) "Unlike" else " Like")
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
