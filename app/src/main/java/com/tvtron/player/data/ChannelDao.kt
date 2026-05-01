package com.tvtron.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE playlistId = :pid ORDER BY sortIndex ASC, id ASC")
    fun observeForPlaylist(pid: Long): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE playlistId = :pid ORDER BY sortIndex ASC, id ASC")
    suspend fun getForPlaylist(pid: Long): List<Channel>

    @Query("SELECT * FROM channels WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Channel?

    @Query("SELECT DISTINCT groupTitle FROM channels WHERE playlistId = :pid ORDER BY groupTitle COLLATE NOCASE ASC")
    fun observeCategories(pid: Long): Flow<List<String>>

    @Query("SELECT * FROM channels ORDER BY playlistId ASC, sortIndex ASC, id ASC")
    fun observeAll(): Flow<List<Channel>>

    @Query("SELECT * FROM channels ORDER BY playlistId ASC, sortIndex ASC, id ASC")
    suspend fun getAll(): List<Channel>

    @Query("SELECT DISTINCT groupTitle FROM channels ORDER BY groupTitle COLLATE NOCASE ASC")
    fun observeAllCategories(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<Channel>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(channel: Channel): Long

    @androidx.room.Update
    suspend fun update(channel: Channel)

    @Query("DELETE FROM channels WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM channels WHERE playlistId = :pid")
    suspend fun deleteForPlaylist(pid: Long)

    /** Used by M3U refresh; preserves manually-added channels. */
    @Query("DELETE FROM channels WHERE playlistId = :pid AND isUserAdded = 0")
    suspend fun deleteAutoForPlaylist(pid: Long)

    @Query("SELECT COALESCE(MAX(sortIndex), -1) FROM channels WHERE playlistId = :pid")
    suspend fun maxSortIndex(pid: Long): Int
}
