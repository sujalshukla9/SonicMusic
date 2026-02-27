package com.sonicmusic.app.player.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import java.util.Locale
import com.sonicmusic.app.R
import com.sonicmusic.app.core.util.ThumbnailUrlUtils
import com.sonicmusic.app.player.playback.PlaybackService
import com.sonicmusic.app.presentation.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val checkIsLiked: suspend (String) -> Boolean,
) : MediaNotification.Provider {
    
    companion object {
        private const val TAG = "MediaNotificationProvider"
        const val CHANNEL_ID = "sonic_music_playback"
        const val NOTIFICATION_ID = 1
        
        // Custom action commands
        // References from PlaybackService for consistency
        const val ACTION_TOGGLE_LIKE = PlaybackService.ACTION_TOGGLE_LIKE
        const val ACTION_TOGGLE_REPEAT = PlaybackService.ACTION_TOGGLE_REPEAT
        const val ACTION_CLOSE = "sonic_music_close"
        private const val ARTWORK_SIZE_PX = 512
        private const val MIN_VALID_EDGE_PX = 180
        private const val MIN_METADATA_EDGE_FOR_DIRECT_USE_PX = 320
        private const val MIN_EXPECTED_HD_WIDTH = 960
        private const val MIN_EXPECTED_HD_HEIGHT = 540
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val imageLoader = context.imageLoader
    @Volatile
    private var activeArtworkUri: String? = null
    private var cachedArtwork: Bitmap? = null
    private var cachedArtworkUri: String? = null
    private val likedStateLock = Any()
    private val likedStateOverrides = mutableMapOf<String, Boolean>()

    private data class DecodedArtwork(
        val bitmap: Bitmap,
        val sourceMinEdgePx: Int
    )
    
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
        val artworkData = metadata?.artworkData
        val mediaId = mediaItem?.mediaId
        
        
        // Load artwork asynchronously
        loadArtworkAsync(artworkUri, mediaId, artworkData) {
            // Callback to refresh notification when artwork is loaded
            onNotificationChangedCallback.onNotificationChanged(
                createNotificationInternal(mediaSession, it)
            )
        }
        
        // Load liked state asynchronously
        resolveLikedStateAsync(mediaItem?.mediaId) {
             onNotificationChangedCallback.onNotificationChanged(
                createNotificationInternal(mediaSession, cachedArtwork)
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
        val mediaId = mediaItem?.mediaId.orEmpty()
        val isLiked = resolveLikedState(mediaId)
        
        val isPlaying = player.isPlaying
        
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
        // Bug 6 fix: Use getService() instead of getForegroundService() —
        // on Android 12+, getService() can be used on Android 9/Oreo since there's no timeout
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
        
        val repeatMode = player.repeatMode
        
        // 1. Like Action (Index 0) - Expanded view only -> NOW IN COMPACT
        actions.add(
            createAction(
                if (isLiked) R.drawable.ic_notification_like else R.drawable.ic_notification_like_outline,
                if (isLiked) "Unlike" else "Like",
                PlaybackService.ACTION_TOGGLE_LIKE,
                0,
                true
            )
        )
        compactViewIndices.add(actions.lastIndex) // Add Like to compact view
        
        // 2. Previous Action (Index 1) - In Compact View -> REMOVED FROM COMPACT
        actions.add(
            createAction(
                R.drawable.ic_notification_prev,
                "Previous",
                PlaybackService.ACTION_PREVIOUS,
                1,
                true 
            )
        )
        // compactViewIndices.add(actions.lastIndex) // Previous hidden in compact
        
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
            .setSmallIcon(R.drawable.ic_notification)
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
        if (artwork != null) {
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
    
    private fun loadArtworkAsync(
        uri: String?,
        mediaId: String?,
        artworkData: ByteArray?,
        onLoaded: (Bitmap?) -> Unit
    ) {
        val artworkKey = uri?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedMediaId = mediaId?.trim()?.takeIf { it.isNotEmpty() }
        val requestKey = artworkKey ?: normalizedMediaId?.let { "media:$it" } ?: "unknown"
        val candidates = ThumbnailUrlUtils.buildCandidates(artworkKey, normalizedMediaId)
        activeArtworkUri = requestKey

        val metadataArtwork = artworkData
            ?.takeIf { it.isNotEmpty() }
            ?.let { decodeMetadataArtwork(it) }
        val metadataFallback = metadataArtwork?.bitmap

        if (metadataArtwork != null && metadataArtwork.sourceMinEdgePx >= MIN_METADATA_EDGE_FOR_DIRECT_USE_PX) {
            updateCachedArtwork(bitmap = metadataArtwork.bitmap, uri = requestKey)
            onLoaded(metadataArtwork.bitmap)
            return
        }

        if (metadataArtwork != null) {
            Log.d(
                TAG,
                "Ignoring low-res metadata artwork (${metadataArtwork.sourceMinEdgePx}px edge) for $requestKey; trying URL candidates"
            )
        }

        // Return cached artwork if URI matches
        if (requestKey == cachedArtworkUri && cachedArtwork != null) {
            onLoaded(cachedArtwork)
            return
        }

        if (candidates.isEmpty()) {
            if (metadataFallback != null) {
                updateCachedArtwork(bitmap = metadataFallback, uri = requestKey)
                onLoaded(metadataFallback)
            } else {
                updateCachedArtwork(bitmap = null, uri = null)
                onLoaded(null)
            }
            return
        }

        // Invalidate stale artwork for the new media item immediately.
        if (requestKey != cachedArtworkUri) {
            updateCachedArtwork(bitmap = null, uri = null)
        }

        scope.launch {
            val bitmap = loadFirstAvailableArtwork(
                requestKey = requestKey,
                candidates = candidates,
                mediaId = normalizedMediaId
            )
            if (activeArtworkUri != requestKey) {
                return@launch
            }

            val finalArtwork = bitmap ?: metadataFallback
            if (finalArtwork != null) {
                updateCachedArtwork(bitmap = finalArtwork, uri = requestKey)
            } else {
                // Keep URI cache empty so transient failures can recover on next update.
                updateCachedArtwork(bitmap = null, uri = null)
            }
            withContext(Dispatchers.Main) { onLoaded(finalArtwork) }
        }
    }

    private fun decodeMetadataArtwork(artworkData: ByteArray): DecodedArtwork? {
        return runCatching {
            val decoded = BitmapFactory.decodeByteArray(artworkData, 0, artworkData.size)
                ?: return null
            if (decoded.width <= 0 || decoded.height <= 0) return null
            val processed = NotificationArtworkProcessor.centerCropRoundedSquare(
                source = decoded,
                targetSize = ARTWORK_SIZE_PX
            )
            DecodedArtwork(
                bitmap = processed,
                sourceMinEdgePx = minOf(decoded.width, decoded.height)
            )
        }.getOrNull()
    }

    private suspend fun loadFirstAvailableArtwork(
        requestKey: String,
        candidates: List<String>,
        mediaId: String?
    ): Bitmap? {
        candidates.forEach { candidate ->
            if (activeArtworkUri != requestKey) return null
            try {
                val request = ImageRequest.Builder(context)
                    .data(candidate)
                    .allowHardware(false)
                    .size(ARTWORK_SIZE_PX)
                    .build()

                val result = imageLoader.execute(request)
                if (result is SuccessResult) {
                    val sourceWidth = result.drawable.intrinsicWidth
                    val sourceHeight = result.drawable.intrinsicHeight
                    if (shouldRejectCandidate(candidate, sourceWidth, sourceHeight, mediaId)) {
                        Log.d(
                            TAG,
                            "Skipping low-quality artwork candidate: $candidate (${sourceWidth}x${sourceHeight})"
                        )
                        return@forEach
                    }

                    return NotificationArtworkProcessor.fromDrawable(
                        drawable = result.drawable,
                        targetSize = ARTWORK_SIZE_PX
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "Artwork candidate failed: $candidate (${e.message})")
            }
        }
        return null
    }

    private fun shouldRejectCandidate(
        candidate: String,
        width: Int,
        height: Int,
        mediaId: String?
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        val minEdge = minOf(width, height)
        if (minEdge < MIN_VALID_EDGE_PX) return true

        val lower = candidate.lowercase(Locale.US)
        val expectsHd = lower.contains("maxresdefault") || lower.contains("hq720")
        if (expectsHd && (width < MIN_EXPECTED_HD_WIDTH || height < MIN_EXPECTED_HD_HEIGHT)) {
            // Many maxres/hq720 URLs return tiny placeholders; keep falling back.
            return true
        }

        // Some media IDs resolve to non-music placeholders on maxres/hq endpoints.
        // If an ID is known and the candidate still comes in tiny, keep trying lower-tier variants.
        if (!mediaId.isNullOrBlank() &&
            (lower.contains("/maxresdefault") || lower.contains("/hq720")) &&
            (width <= 400 || height <= 225)
        ) {
            return true
        }
        return false
    }

    private fun updateCachedArtwork(bitmap: Bitmap?, uri: String?) {
        cachedArtwork = bitmap
        cachedArtworkUri = uri
    }
    
    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle
    ): Boolean {
        return when (action) {
            ACTION_TOGGLE_LIKE -> {
                context.startService(
                    Intent(context, PlaybackService::class.java).apply {
                        this.action = PlaybackService.ACTION_TOGGLE_LIKE
                    }
                )
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
        activeArtworkUri = null
        updateCachedArtwork(bitmap = null, uri = null)
        synchronized(likedStateLock) {
            likedStateOverrides.clear()
        }
    }

    fun refreshNotification(mediaSession: MediaSession) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val mediaNotification = createNotificationInternal(mediaSession, cachedArtwork)
        manager.notify(NOTIFICATION_ID, mediaNotification.notification)
    }

    fun setLikedStateOverride(songId: String, isLiked: Boolean) {
        if (songId.isBlank()) return
        synchronized(likedStateLock) {
            likedStateOverrides[songId] = isLiked
        }
    }

    fun clearLikedStateOverride(songId: String) {
        if (songId.isBlank()) return
        synchronized(likedStateLock) {
            likedStateOverrides.remove(songId)
        }
    }

    private fun resolveLikedState(mediaId: String): Boolean {
        if (mediaId.isBlank()) return false
        // If override exists, use it
        synchronized(likedStateLock) {
            likedStateOverrides[mediaId]
        }?.let { return it }
        
        // Return cached value if available, otherwise false (will be updated by async check)
        return likedStateCache[mediaId] ?: false
    }

    private val likedStateCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile
    private var activeLikedId: String? = null

    private fun resolveLikedStateAsync(mediaId: String?, onStateLoaded: () -> Unit) {
        val songId = mediaId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        activeLikedId = songId
        
        // If we have an override, no need to fetch
        synchronized(likedStateLock) {
            if (likedStateOverrides.containsKey(songId)) return
        }

        // If explicitly cached, we are good? 
        // We might want to refresh anyway to be sure.
        
        scope.launch {
            if (activeLikedId != songId) return@launch
            
            val isLiked = try {
                checkIsLiked(songId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check liked state", e)
                false
            }
            
            if (activeLikedId != songId) return@launch
            
            val oldState = likedStateCache[songId]
            likedStateCache[songId] = isLiked
            
            if (oldState != isLiked) {
                withContext(Dispatchers.Main) {
                    onStateLoaded()
                }
            }
        }
    }
}
