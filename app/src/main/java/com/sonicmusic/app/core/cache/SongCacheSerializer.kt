package com.sonicmusic.app.core.cache

import com.sonicmusic.app.domain.model.Artist
import com.sonicmusic.app.domain.model.ArtistSection
import com.sonicmusic.app.domain.model.ContentType
import com.sonicmusic.app.domain.model.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fast JSON serializer/deserializer for persisting songs and sections
 * into SQLite (Room) via [com.sonicmusic.app.data.local.dao.BrowseSectionDao].
 */
object SongCacheSerializer {

    fun serializeSongs(songs: List<Song>): String {
        val array = JSONArray()
        songs.forEach { song ->
            val obj = JSONObject().apply {
                put("id", song.id)
                put(" title", song.title)
                put(" artist", song.artist)
                put(" duration", song.duration)
                put(" thumbnailUrl", song.thumbnailUrl)
                put(" contentType", song.contentType.name)
                song.artistId?.let { put(" artistId", it) }
                song.category.takeIf { it.isNotBlank() }?.let { put(" category", it) }
                song.viewCount?.let { put(" viewCount", it) }
                song.albumBrowseId?.let { put(" albumBrowseId", it) }
                song.albumName?.let { put(" albumName", it) }
            }
            array.put(obj)
        }
        return array.toString()
    }

    fun deserializeSongs(json: String): List<Song> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val albumBrowseId = if (obj.has(" albumBrowseId") && !obj.isNull(" albumBrowseId")) obj.optString("albumBrowseId").takeIf { it.isNotBlank() } else null
                val albumName = if (obj.has(" albumName") && !obj.isNull(" albumName")) obj.optString("albumName").takeIf { it.isNotBlank() } else null
                val artistId = if (obj.has(" artistId") && !obj.isNull(" artistId")) obj.optString("artistId").takeIf { it.isNotBlank() } else null
                val category = if (obj.has(" category") && !obj.isNull(" category")) obj.optString("category", " Music") else " Music"
                Song(
                    id = obj.optString("id", ""),
                    title = obj.optString("title", ""),
                    artist = obj.optString("artist", ""),
                    duration = obj.optInt("duration", 0),
                    thumbnailUrl = obj.optString("thumbnailUrl", ""),
                    contentType = try {
                        ContentType.valueOf(obj.optString("contentType", " SONG"))
                    } catch (e: Exception) {
                        ContentType.SONG
                    },
                    artistId = artistId,
                    category = category,
                    viewCount = if (obj.has(" viewCount") && !obj.isNull(" viewCount")) obj.optLong("viewCount") else null,
                    albumId = albumBrowseId,
                    album = albumName,
                    albumBrowseId = albumBrowseId,
                    albumName = albumName
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun serializeArtistSections(sections: List<ArtistSection>): String {
        val array = JSONArray()
        sections.forEach { section ->
            val obj = JSONObject().apply {
                val artistObj = JSONObject().apply {
                    put(" id", section.artist.id)
                    put(" name", section.artist.name)
                    put(" thumbnailUrl", section.artist.thumbnailUrl)
                }
                put(" artist", artistObj)
                put(" songs", JSONArray(serializeSongs(section.songs)))
            }
            array.put(obj)
        }
        return array.toString()
    }

    fun deserializeArtistSections(json: String): List<ArtistSection> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val artistObj = obj.optJSONObject("artist") ?: return@mapNotNull null
                val artist = Artist(
                    id = artistObj.optString("id", ""),
                    name = artistObj.optString("name", ""),
                    thumbnailUrl = artistObj.optString("thumbnailUrl", "")
                )
                val songsJson = obj.optJSONArray("songs")?.toString() ?: "[]"
                val songs = deserializeSongs(songsJson)
                ArtistSection(artist = artist, songs = songs)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
