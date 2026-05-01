package com.tvtron.player.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY sortIndex ASC, id ASC")
    fun observeAll(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists ORDER BY sortIndex ASC, id ASC")
    suspend fun getAll(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Playlist?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: Playlist): Long

    @Update suspend fun update(p: Playlist)

    @Delete suspend fun delete(p: Playlist)

    @Query("UPDATE playlists SET lastRefresh = :ts WHERE id = :id")
    suspend fun touchLastRefresh(id: Long, ts: Long)
}
