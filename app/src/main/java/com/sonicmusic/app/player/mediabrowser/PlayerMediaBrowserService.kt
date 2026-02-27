package com.sonicmusic.app.player.mediabrowser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.service.media.MediaBrowserService
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.core.os.bundleOf
import com.sonicmusic.app.R
import com.sonicmusic.app.player.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import android.media.MediaDescription as BrowserMediaDescription
import android.media.browse.MediaBrowser.MediaItem as BrowserMediaItem
import androidx.media3.common.Player

/**
 * Media Browser Service for Android Auto, Google Assistant, and Wear OS
 * 
 * Allows external apps to:
 * - Browse songs, playlists, and albums
 * - Control playback (play, pause, skip)
 * - Search for music
 * 
 * This creates a bridge between Media3's MediaSession and the legacy MediaBrowserService API.
 */
class PlayerMediaBrowserService : MediaBrowserService(), ServiceConnection {
    
    companion object {
        private const val TAG = "MediaBrowserService"
    }
    
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var bound = false
    private var bindRequested = false
    private var player: androidx.media3.exoplayer.ExoPlayer? = null
    private var media3Controller: androidx.media3.session.MediaController? = null
    private var playerListener: Player.Listener? = null
    private var legacySession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        
        // Create a legacy MediaSession for Android Auto compatibility
        legacySession = MediaSession(this, "SonicMusicBrowser").apply {
            setCallback(LegacySessionCallback())
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
        }
        
        // Set the session token for the browser service
        sessionToken = legacySession?.sessionToken
    }

    override fun onDestroy() {
        // Remove listener before unbinding
        playerListener?.let { listener ->
            media3Controller?.removeListener(listener)
        }
        playerListener = null
        media3Controller?.release()
        media3Controller = null
        player = null
        legacySession?.release()
        if (bound || bindRequested) {
            try {
                unbindService(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding service", e)
            }
            bound = false
            bindRequested = false
        }
        super.onDestroy()
    }

    override fun onServiceConnected(className: ComponentName, service: IBinder) {
        Log.d(TAG, "Connected to PlaybackService")
        bindRequested = false
        bound = true

        // Use Media3 MediaController to observe playback state from PlaybackService's MediaSession.
        // We can't use a LocalBinder because PlaybackService extends MediaSessionService and
        // a custom onBind would intercept Media3's IPC channel.
        val sessionToken = androidx.media3.session.SessionToken(
            this, ComponentName(this, PlaybackService::class.java)
        )
        val controllerFuture = androidx.media3.session.MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            try {
                val controller = controllerFuture.get()
                media3Controller = controller

                // Register a listener to keep the legacy session in sync
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updatePlaybackState()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updatePlaybackState()
                    }
                }
                playerListener = listener
                controller.addListener(listener)

                Log.d(TAG, "Media3 MediaController connected for state sync")
                updatePlaybackState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build MediaController", e)
            }
        }, { it.run() })

        Log.d(TAG, "PlaybackService bound successfully")
    }

    override fun onServiceDisconnected(name: ComponentName) {
        Log.d(TAG, "Disconnected from PlaybackService")
        playerListener?.let { listener ->
            media3Controller?.removeListener(listener)
        }
        playerListener = null
        media3Controller?.release()
        media3Controller = null
        player = null
        bound = false
        bindRequested = false
    }

    private fun ensurePlaybackServiceBound() {
        if (bound || bindRequested) return
        val intent = Intent(this, PlaybackService::class.java)
        bindRequested = true
        val bindOk = runCatching {
            bindService(intent, this, Context.BIND_AUTO_CREATE)
        }.getOrElse { error ->
            Log.e(TAG, "Failed to bind PlaybackService", error)
            false
        }
        if (!bindOk) {
            bindRequested = false
        }
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        Log.d(TAG, "onGetRoot from: $clientPackageName")
        
        // Bind lazily once and avoid leaking repeated service connections.
        ensurePlaybackServiceBound()
        
        return BrowserRoot(
            MediaId.ROOT.id,
            bundleOf("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT" to 1)
        )
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<BrowserMediaItem>>
    ) {
        Log.d(TAG, "onLoadChildren: $parentId")
        
        // Return browsable categories
        result.sendResult(
            when (MediaId(parentId)) {
                MediaId.ROOT -> mutableListOf(
                    createBrowsableItem(MediaId.SONGS, "Songs", R.drawable.ic_notification_small),
                    createBrowsableItem(MediaId.FAVORITES, "Favorites", R.drawable.ic_notification_small)
                )
                
                // For now, return empty lists - songs are fetched from queue
                MediaId.SONGS, MediaId.FAVORITES -> mutableListOf()
                
                else -> mutableListOf()
            }
        )
    }

    private fun createBrowsableItem(
        mediaId: MediaId,
        title: String,
        @DrawableRes iconRes: Int
    ): BrowserMediaItem {
        return BrowserMediaItem(
            BrowserMediaDescription.Builder()
                .setMediaId(mediaId.id)
                .setTitle(title)
                .setIconUri(uriFor(iconRes))
                .build(),
            BrowserMediaItem.FLAG_BROWSABLE
        )
    }

    private fun uriFor(@DrawableRes id: Int): Uri = Uri.Builder()
        .scheme(android.content.ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(resources.getResourcePackageName(id))
        .appendPath(resources.getResourceTypeName(id))
        .appendPath(resources.getResourceEntryName(id))
        .build()

    private fun updatePlaybackState() {
        val ctrl = media3Controller
        val state = when {
            ctrl == null -> PlaybackState.STATE_NONE
            ctrl.isPlaying -> PlaybackState.STATE_PLAYING
            ctrl.playbackState == Player.STATE_BUFFERING -> PlaybackState.STATE_BUFFERING
            ctrl.playWhenReady -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_STOPPED
        }
        val position = ctrl?.currentPosition ?: 0L
        val speed = ctrl?.playbackParameters?.speed ?: 1f

        legacySession?.setPlaybackState(
            PlaybackState.Builder()
                .setState(state, position, speed)
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO
                )
                .build()
        )
    }

    /**
     * Legacy Session Callback for handling playback commands from external apps
     */
    private inner class LegacySessionCallback : MediaSession.Callback() {
        
        override fun onPlay() {
            Log.d(TAG, "onPlay")
            startService(
                Intent(this@PlayerMediaBrowserService, PlaybackService::class.java).apply {
                    action = PlaybackService.ACTION_PLAY
                }
            )
        }
        
        override fun onPause() {
            Log.d(TAG, "onPause")
            startService(
                Intent(this@PlayerMediaBrowserService, PlaybackService::class.java).apply {
                    action = PlaybackService.ACTION_PAUSE
                }
            )
        }
        
        override fun onSkipToPrevious() {
            Log.d(TAG, "onSkipToPrevious")
            startService(
                Intent(this@PlayerMediaBrowserService, PlaybackService::class.java).apply {
                    action = PlaybackService.ACTION_PREVIOUS
                }
            )
        }
        
        override fun onSkipToNext() {
            Log.d(TAG, "onSkipToNext")
            startService(
                Intent(this@PlayerMediaBrowserService, PlaybackService::class.java).apply {
                    action = PlaybackService.ACTION_NEXT
                }
            )
        }
        
        override fun onSeekTo(pos: Long) {
            Log.d(TAG, "onSeekTo: $pos")
            // Seek is not easily done via intent, but the MediaSession in PlaybackService handles it
        }
        
        override fun onSkipToQueueItem(id: Long) {
            Log.d(TAG, "onSkipToQueueItem: $id")
            // Queue navigation is handled by the MediaSession in PlaybackService
        }
        
        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            Log.d(TAG, "onPlayFromSearch: $query")
            // TODO: Implement search functionality
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            Log.d(TAG, "onPlayFromMediaId: $mediaId")
            // Playback is handled by the main UI
        }
    }

    /**
     * Media IDs for browsing structure
     */
    @JvmInline
    private value class MediaId(val id: String) : CharSequence by id {
        companion object {
            val ROOT = MediaId("root")
            val SONGS = MediaId("songs")
            val FAVORITES = MediaId("favorites")
        }

        operator fun div(other: String) = MediaId("$id/$other")
    }
}
