package com.sonicmusic.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sonicmusic.app.data.local.entity.FollowedArtistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FollowedArtistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun follow(artist: FollowedArtistEntity)

    @Query("DELETE FROM followed_artists WHERE artistName = :artistName COLLATE NOCASE")
    suspend fun unfollow(artistName: String)

    @Query("DELETE FROM followed_artists WHERE browseId = :browseId")
    suspend fun unfollowByBrowseId(browseId: String)

    @Query("SELECT * FROM followed_artists ORDER BY followedAt DESC")
    fun getAllFollowed(): Flow<List<FollowedArtistEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM followed_artists WHERE artistName = :artistName COLLATE NOCASE)")
    fun isFollowed(artistName: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM followed_artists WHERE browseId = :browseId)")
    fun isFollowedByBrowseId(browseId: String): Flow<Boolean>

    @Query("SELECT COUNT(*) FROM followed_artists")
    fun getFollowedCount(): Flow<Int>
}
