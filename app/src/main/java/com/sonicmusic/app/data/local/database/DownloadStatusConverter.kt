package com.sonicmusic.app.data.local.database

import androidx.room.TypeConverter
import com.sonicmusic.app.data.local.entity.DownloadStatus

class DownloadStatusConverter {
    @TypeConverter
    fun fromStatus(status: DownloadStatus): String {
        return status.name
    }

    @TypeConverter
    fun toStatus(value: String): DownloadStatus {
        return try {
            DownloadStatus.valueOf(value)
        } catch (e: Exception) {
            DownloadStatus.FAILED
        }
    }
}
