package com.sonicmusic.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.sonicmusic.app.presentation.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    
    companion object {
        private const val CHANNEL_ID = "sonic_music_playback_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_PLAY = "com.sonicmusic.app.PLAY"
        const val ACTION_PAUSE = "com.sonicmusic.app.PAUSE"
        const val ACTION_NEXT = "com.sonicmusic.app.NEXT"
        const val ACTION_PREVIOUS = "com.sonicmusic.app.PREVIOUS"
        const val EXTRA_SONG_ID = "song_id"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_THUMBNAIL = "thumbnail"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializePlayer()
    }
    
    private fun initializePlayer() {
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
            }
            
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateNotification()
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    // Handle song completion
                }
            }
        })
        
        mediaSession = player?.let { exoPlayer ->
            MediaSession.Builder(this, exoPlayer)
                .setCallback(object : MediaSession.Callback {
                    override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
                        // Allow all controllers
                    }
                })
                .build()
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Unknown"
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: "Unknown Artist"
                val thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL) ?: ""
                
                if (streamUrl != null) {
                    playSong(streamUrl, title, artist, thumbnailUrl)
                } else {
                    player?.play()
                }
            }
            ACTION_PAUSE -> player?.pause()
            ACTION_NEXT -> player?.seekToNext()
            ACTION_PREVIOUS -> {
                if (player?.currentPosition ?: 0 > 3000) {
                    player?.seekTo(0)
                } else {
                    player?.seekToPrevious()
                }
            }
        }
        updateNotification()
        return START_STICKY
    }
    
    private fun playSong(streamUrl: String, title: String, artist: String, thumbnailUrl: String) {
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(android.net.Uri.parse(thumbnailUrl))
                    .build()
            )
            .build()
        
        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            play()
        }
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
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun updateNotification() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }
    
    private fun createNotification(): Notification {
        val exoPlayer = player ?: return createDefaultNotification()
        
        val mediaItem = exoPlayer.currentMediaItem
        val title = mediaItem?.mediaMetadata?.title?.toString() ?: "Unknown"
        val artist = mediaItem?.mediaMetadata?.artist?.toString() ?: "Unknown Artist"
        
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Action intents
        val playIntent = Intent(this, PlaybackService::class.java).setAction(ACTION_PLAY)
        val playPendingIntent = PendingIntent.getService(
            this, 1, playIntent, PendingIntent.FLAG_IMMUTABLE
        )
        
        val pauseIntent = Intent(this, PlaybackService::class.java).setAction(ACTION_PAUSE)
        val pausePendingIntent = PendingIntent.getService(
            this, 2, pauseIntent, PendingIntent.FLAG_IMMUTABLE
        )
        
        val nextIntent = Intent(this, PlaybackService::class.java).setAction(ACTION_NEXT)
        val nextPendingIntent = PendingIntent.getService(
            this, 3, nextIntent, PendingIntent.FLAG_IMMUTABLE
        )
        
        val previousIntent = Intent(this, PlaybackService::class.java).setAction(ACTION_PREVIOUS)
        val previousPendingIntent = PendingIntent.getService(
            this, 4, previousIntent, PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", previousPendingIntent)
        
        if (exoPlayer.isPlaying) {
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePendingIntent)
        } else {
            builder.addAction(android.R.drawable.ic_media_play, "Play", playPendingIntent)
        }
        
        builder.addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)
        
        return builder.build()
    }
    
    private fun createDefaultNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SonicMusic")
            .setContentText("Ready to play")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
    
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }
    
    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        player = null
        mediaSession = null
        super.onDestroy()
    }
}