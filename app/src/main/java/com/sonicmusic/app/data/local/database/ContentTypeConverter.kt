package com.sonicmusic.app.data.local.database

import androidx.room.TypeConverter
import com.sonicmusic.app.domain.model.ContentType

class ContentTypeConverter {
    @TypeConverter
    fun fromContentType(contentType: ContentType): String {
        return contentType.name
    }

    @TypeConverter
    fun toContentType(value: String): ContentType {
        return try {
            ContentType.valueOf(value)
        } catch (e: Exception) {
            ContentType.SONG
        }
    }
}
