package com.tvtron.player.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tvtron.player.data.AppDatabase
import com.tvtron.player.data.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Single seam for fetching M3U/XMLTV bytes (remote http or local content uri),
 * parsing, and writing channels + EPG to Room.
 */
object PlaylistRepository {

    private const val TAG = "PlaylistRepository"

    suspend fun refresh(context: Context, playlist: Playlist) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val m3uText = readText(context, playlist.source) ?: run {
            Log.w(TAG, "refresh(${playlist.id}): could not read M3U from ${playlist.source}")
            return@withContext
        }
        val parsed = M3uParser.parse(m3uText)
        Log.i(TAG, "refresh(${playlist.id}): parsed ${parsed.channels.size} channels, urlTvg='${parsed.urlTvg}'")

        // Re-import only auto-imported channels; user-added entries stay put.
        val newChannels = parsed.channels.mapIndexed { idx, p -> p.toEntity(playlist.id, idx) }
        db.channelDao().deleteAutoForPlaylist(playlist.id)
        if (newChannels.isNotEmpty()) db.channelDao().insertAll(newChannels)
        // Push surviving user-added channels to the end so they sort after the imported ones.
        val userAdded = db.channelDao().getForPlaylist(playlist.id).filter { it.isUserAdded }
        userAdded.forEachIndexed { i, ch ->
            db.channelDao().update(ch.copy(sortIndex = newChannels.size + i))
        }

        val epgUrl = playlist.epgUrl.ifBlank { parsed.urlTvg }
        if (epgUrl.isNotBlank()) {
            refreshEpg(context, playlist, epgUrl)
        } else {
            Log.i(TAG, "refresh(${playlist.id}): no EPG URL (epgUrl override blank, no url-tvg/tvg-url/x-tvg-url in M3U header)")
        }
        db.playlistDao().touchLastRefresh(playlist.id, System.currentTimeMillis())
    }

    suspend fun refreshEpg(context: Context, playlist: Playlist, epgUrl: String) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val daysBack = SettingsManager.getEpgDaysBack(context)
        val daysFwd = SettingsManager.getEpgDaysForward(context)
        val now = System.currentTimeMillis()
        val keepFrom = now - daysBack * 86_400_000L
        val keepUntil = now + daysFwd * 86_400_000L

        Log.i(TAG, "refreshEpg(${playlist.id}): fetching $epgUrl (window ${daysBack}d back / ${daysFwd}d fwd)")
        val rawStream = openStream(context, epgUrl) ?: run {
            Log.w(TAG, "refreshEpg(${playlist.id}): openStream returned null for $epgUrl")
            return@withContext
        }
        try {
            val sniffed = wrapGzipIfNeeded(rawStream)
            val parsed = XmltvParser.parse(sniffed, playlist.id, keepFrom, keepUntil)
            Log.i(TAG, "refreshEpg(${playlist.id}): parsed ${parsed.channels.size} channels, ${parsed.programs.size} programs")
            db.epgDao().deleteProgramsForPlaylist(playlist.id)
            db.epgDao().deleteChannelsForPlaylist(playlist.id)
            if (parsed.channels.isNotEmpty()) db.epgDao().insertChannels(parsed.channels)
            if (parsed.programs.isNotEmpty()) db.epgDao().insertPrograms(parsed.programs)
            // Mark a successful EPG pull only when at least one program landed.
            if (parsed.programs.isNotEmpty()) {
                SettingsManager.setLastEpgRefresh(context, System.currentTimeMillis())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "refreshEpg(${playlist.id}): parse failed", t)
        } finally {
            runCatching { rawStream.close() }
        }
    }

    /** Refresh just the EPG for all playlists (uses each playlist's epgUrl override or its M3U url-tvg). */
    suspend fun refreshAllEpg(context: Context) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        for (p in db.playlistDao().getAll()) {
            val epgUrl = p.epgUrl.takeIf { it.isNotBlank() }
                ?: M3uParser.parse(readText(context, p.source).orEmpty()).urlTvg
            if (epgUrl.isBlank()) {
                Log.i(TAG, "refreshAllEpg: skip ${p.id} (no EPG URL)")
                continue
            }
            runCatching { refreshEpg(context, p, epgUrl) }
                .onFailure { Log.e(TAG, "refreshAllEpg(${p.id}) failed", it) }
        }
    }

    private fun readText(context: Context, source: String): String? {
        val raw = openStream(context, source) ?: return null
        return raw.use { wrapGzipIfNeeded(it).bufferedReader(Charsets.UTF_8).readText() }
    }

    private fun openStream(context: Context, source: String): InputStream? = when {
        source.startsWith("http://", true) || source.startsWith("https://", true) -> {
            val client = HttpClientFactory.get(context)
            // Set explicit Accept-Encoding so OkHttp does NOT transparently gunzip the body —
            // many EPG endpoints serve a real .gz blob and we'll wrap in GZIPInputStream below.
            val req = Request.Builder().url(source)
                .header("User-Agent", "TVTron/1.0")
                .header("Accept-Encoding", "identity")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "openStream($source): HTTP ${resp.code}")
                resp.close()
                null
            } else resp.body?.byteStream()
        }
        source.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(source))
        source.startsWith("file://") -> context.contentResolver.openInputStream(Uri.parse(source))
        else -> null
    }

    /** Sniffs the first two bytes for gzip magic (0x1F 0x8B); wraps in GZIPInputStream if matched. */
    private fun wrapGzipIfNeeded(stream: InputStream): InputStream {
        val buf = if (stream is BufferedInputStream) stream else BufferedInputStream(stream)
        buf.mark(2)
        val b1 = buf.read()
        val b2 = buf.read()
        buf.reset()
        return if (b1 == 0x1F && b2 == 0x8B) GZIPInputStream(buf) else buf
    }
}
