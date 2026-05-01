package com.tvtron.player.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tvtron.player.data.AutoRefreshMode
import com.tvtron.player.data.Playlist
import java.util.concurrent.TimeUnit

object RefreshScheduler {

    fun applyAll(context: Context, playlists: List<Playlist>) {
        playlists.forEach { apply(context, it) }
    }

    fun apply(context: Context, playlist: Playlist) {
        val wm = WorkManager.getInstance(context)
        val unique = PlaylistRefreshWorker.uniqueName(playlist.id)
        when (playlist.autoRefresh) {
            AutoRefreshMode.OFF, AutoRefreshMode.ON_LAUNCH -> {
                wm.cancelUniqueWork(unique)
            }
            AutoRefreshMode.SCHEDULED -> {
                val hours = playlist.scheduleHours.coerceAtLeast(1).toLong()
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(if (playlist.isRemote) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
                    .build()
                val req = PeriodicWorkRequestBuilder<PlaylistRefreshWorker>(hours, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .setInputData(Data.Builder().putLong(PlaylistRefreshWorker.KEY_PLAYLIST_ID, playlist.id).build())
                    .build()
                wm.enqueueUniquePeriodicWork(unique, ExistingPeriodicWorkPolicy.UPDATE, req)
            }
        }
    }

    fun runOnceNow(context: Context, playlist: Playlist) {
        val wm = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (playlist.isRemote) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()
        val req = OneTimeWorkRequestBuilder<PlaylistRefreshWorker>()
            .setConstraints(constraints)
            .setInputData(Data.Builder().putLong(PlaylistRefreshWorker.KEY_PLAYLIST_ID, playlist.id).build())
            .build()
        wm.enqueueUniqueWork(
            PlaylistRefreshWorker.uniqueName(playlist.id) + "-once",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    fun cancel(context: Context, playlistId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(PlaylistRefreshWorker.uniqueName(playlistId))
    }
}
