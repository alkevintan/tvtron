package com.tvtron.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT channelId FROM favorites WHERE playlistId = :pid")
    fun observeIds(pid: Long): Flow<List<Long>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE channelId = :cid)")
    suspend fun isFavorite(cid: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(f: Favorite): Long

    @Query("DELETE FROM favorites WHERE channelId = :cid")
    suspend fun remove(cid: Long)
}
