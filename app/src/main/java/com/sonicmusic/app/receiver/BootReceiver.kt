package com.sonicmusic.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sonicmusic.app.data.downloadmanager.SongDownloadManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var downloadManager: SongDownloadManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", " BOOT_COMPLETED received. SongDownloadManager injected to auto-resume pending downloads.")
        }
    }
}
