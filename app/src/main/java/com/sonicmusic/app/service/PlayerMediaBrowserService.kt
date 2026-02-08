package com.sonicmusic.app.service

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import android.media.MediaDescription as BrowserMediaDescription
import android.media.browse.MediaBrowser.MediaItem as BrowserMediaItem

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
    private var playerBinder: PlaybackService.Binder? = null
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
        legacySession?.release()
        if (bound) {
            try {
                unbindService(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding service", e)
            }
        }
        super.onDestroy()
    }

    override fun onServiceConnected(className: ComponentName, service: IBinder) {
        Log.d(TAG, "Connected to PlaybackService")
        if (service is PlaybackService.Binder) {
            bound = true
            playerBinder = service
            updatePlaybackState()
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        Log.d(TAG, "Disconnected from PlaybackService")
        bound = false
        playerBinder = null
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        Log.d(TAG, "onGetRoot from: $clientPackageName")
        
        // Bind to PlaybackService
        val intent = Intent(this, PlaybackService::class.java)
        bindService(intent, this, Context.BIND_AUTO_CREATE)
        
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
        playerBinder?.let { binder ->
            val state = if (binder.player.isPlaying) {
                PlaybackState.STATE_PLAYING
            } else {
                PlaybackState.STATE_PAUSED
            }
            
            legacySession?.setPlaybackState(
                PlaybackState.Builder()
                    .setState(state, binder.player.currentPosition, 1f)
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
    }

    /**
     * Legacy Session Callback for handling playback commands from external apps
     */
    private inner class LegacySessionCallback : MediaSession.Callback() {
        
        override fun onPlay() {
            Log.d(TAG, "onPlay")
            playerBinder?.player?.play()
            updatePlaybackState()
        }
        
        override fun onPause() {
            Log.d(TAG, "onPause")
            playerBinder?.player?.pause()
            updatePlaybackState()
        }
        
        override fun onSkipToPrevious() {
            Log.d(TAG, "onSkipToPrevious")
            playerBinder?.player?.let { player ->
                if (player.hasPreviousMediaItem()) {
                    player.seekToPrevious()
                }
            }
            updatePlaybackState()
        }
        
        override fun onSkipToNext() {
            Log.d(TAG, "onSkipToNext")
            playerBinder?.player?.let { player ->
                if (player.hasNextMediaItem()) {
                    player.seekToNext()
                }
            }
            updatePlaybackState()
        }
        
        override fun onSeekTo(pos: Long) {
            Log.d(TAG, "onSeekTo: $pos")
            playerBinder?.player?.seekTo(pos)
            updatePlaybackState()
        }
        
        override fun onSkipToQueueItem(id: Long) {
            Log.d(TAG, "onSkipToQueueItem: $id")
            playerBinder?.player?.seekToDefaultPosition(id.toInt())
            updatePlaybackState()
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
