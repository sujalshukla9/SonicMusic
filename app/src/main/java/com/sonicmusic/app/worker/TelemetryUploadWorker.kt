package com.sonicmusic.app.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sonicmusic.app.data.local.dao.PlaybackHistoryDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

/**
 * Worker responsible for batch-uploading User Playback Telemetry
 * to the recommendation engine backend.
 *
 * Runs periodically (e.g., every 6 hours) on unmetered networks
 * to preserve battery and mobile data.
 */
@HiltWorker
class TelemetryUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val playbackHistoryDao: PlaybackHistoryDao
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "TelemetryUploadWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🚀 Starting telemetry batch upload...")
            
            // 1. Gather telemetry data from local database
            // Note: We use recent playback history to define skip vectors and completion ratios.
            // When building the Two-Tower model, this is the primary 'implicit context' signal.
            val recentPlaybacks = playbackHistoryDao.getRecentlyPlayed(100).first()
            
            if (recentPlaybacks.isEmpty()) {
                Log.d(TAG, "No new telemetry data to upload. Skipping.")
                return@withContext Result.success()
            }
            
            // 2. Prepare JSON payload
            // (In a real app, parse `recentPlaybacks` to a JSON array and HTTP POST to backend)
            val telemetryCount = recentPlaybacks.size
            Log.d(TAG, "📦 Prepared batch payload with $telemetryCount playback sessions.")
            
            // 3. Simulate Network Upload (Fake delay)
            kotlinx.coroutines.delay(1500)
            
            Log.d(TAG, "✅ Telemetry batch upload successful.")
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Telemetry upload failed", e)
            Result.retry()
        }
    }
}
