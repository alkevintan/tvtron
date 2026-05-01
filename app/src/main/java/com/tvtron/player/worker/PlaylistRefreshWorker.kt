package com.tvtron.player.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tvtron.player.data.AppDatabase
import com.tvtron.player.util.PlaylistRepository

class PlaylistRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val playlistId = inputData.getLong(KEY_PLAYLIST_ID, -1L)
        if (playlistId == -1L) return Result.failure()
        val db = AppDatabase.getInstance(applicationContext)
        val playlist = db.playlistDao().getById(playlistId) ?: return Result.failure()
        return try {
            PlaylistRepository.refresh(applicationContext, playlist)
            Result.success()
        } catch (t: Throwable) {
            android.util.Log.w("PlaylistRefreshWorker", "refresh failed for $playlistId", t)
            Result.retry()
        }
    }

    companion object {
        const val KEY_PLAYLIST_ID = "playlist_id"
        fun uniqueName(playlistId: Long) = "tvtron-refresh-$playlistId"
    }
}
