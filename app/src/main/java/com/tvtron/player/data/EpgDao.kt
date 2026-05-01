package com.tvtron.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EpgDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(items: List<EpgChannel>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrograms(items: List<EpgProgram>)

    @Query("DELETE FROM epg_programs WHERE playlistId = :pid")
    suspend fun deleteProgramsForPlaylist(pid: Long)

    @Query("DELETE FROM epg_channels WHERE playlistId = :pid")
    suspend fun deleteChannelsForPlaylist(pid: Long)

    @Query("DELETE FROM epg_programs WHERE stop < :keepFrom OR start > :keepUntil")
    suspend fun pruneOutsideWindow(keepFrom: Long, keepUntil: Long)

    @Query("""
        SELECT * FROM epg_programs
        WHERE playlistId = :pid AND xmltvId = :xmltvId AND stop > :now
        ORDER BY start ASC LIMIT :limit
    """)
    suspend fun getUpcoming(pid: Long, xmltvId: String, now: Long, limit: Int = 5): List<EpgProgram>

    @Query("""
        SELECT * FROM epg_programs
        WHERE playlistId = :pid AND xmltvId = :xmltvId AND start <= :now AND stop > :now
        LIMIT 1
    """)
    suspend fun getCurrent(pid: Long, xmltvId: String, now: Long): EpgProgram?

    @Query("""
        SELECT * FROM epg_programs
        WHERE playlistId = :pid AND xmltvId = :xmltvId AND start <= :now AND stop > :now
        LIMIT 1
    """)
    fun observeCurrent(pid: Long, xmltvId: String, now: Long): Flow<EpgProgram?>

    @Query("""
        SELECT DISTINCT xmltvId FROM epg_programs
        WHERE playlistId = :pid AND title LIKE '%' || :q || '%'
    """)
    suspend fun searchProgramTitlesXmltvIds(pid: Long, q: String): List<String>
}
