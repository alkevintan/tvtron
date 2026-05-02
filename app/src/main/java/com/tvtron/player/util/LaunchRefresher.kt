package com.tvtron.player.util

import android.content.Context
import com.tvtron.player.data.AppDatabase
import com.tvtron.player.data.AutoRefreshMode
import com.tvtron.player.worker.RefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide once-per-launch playlist refresh. Splash kicks this off and
 * awaits it (with a cap) so MainActivity opens with channels already populated.
 * MainViewModel also calls start() as a safety net — the AtomicBoolean keeps
 * it idempotent.
 */
object LaunchRefresher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    @Volatile private var current: Job? = null

    fun start(context: Context): Job {
        current?.let { return it }
        if (!started.compareAndSet(false, true)) return current ?: scope.launch { }
        val app = context.applicationContext
        val job = scope.launch {
            val playlists = AppDatabase.getInstance(app).playlistDao().getAll()
            playlists.filter { it.autoRefresh == AutoRefreshMode.ON_LAUNCH }
                .map { p -> async { runCatching { PlaylistRepository.refresh(app, p) } } }
                .awaitAll()
            RefreshScheduler.applyAll(app, playlists)
        }
        current = job
        return job
    }
}
