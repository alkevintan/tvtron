package com.tvtron.player.util

import android.content.Context
import android.net.Uri
import com.tvtron.player.data.AppDatabase
import com.tvtron.player.data.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Single seam for fetching M3U/XMLTV bytes (remote http or local content uri),
 * parsing, and writing channels + EPG to Room.
 */
object PlaylistRepository {

    suspend fun refresh(context: Context, playlist: Playlist) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val m3uText = readText(context, playlist.source) ?: return@withContext
        val parsed = M3uParser.parse(m3uText)

        val channels = parsed.channels.mapIndexed { idx, p -> p.toEntity(playlist.id, idx) }
        db.channelDao().deleteForPlaylist(playlist.id)
        if (channels.isNotEmpty()) db.channelDao().insertAll(channels)

        val epgUrl = playlist.epgUrl.ifBlank { parsed.urlTvg }
        if (epgUrl.isNotBlank()) {
            refreshEpg(context, playlist, epgUrl)
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

        val stream = openStream(context, epgUrl, gunzipIfNeeded = true) ?: return@withContext
        stream.use {
            val parsed = XmltvParser.parse(it, playlist.id, keepFrom, keepUntil)
            db.epgDao().deleteProgramsForPlaylist(playlist.id)
            db.epgDao().deleteChannelsForPlaylist(playlist.id)
            if (parsed.channels.isNotEmpty()) db.epgDao().insertChannels(parsed.channels)
            if (parsed.programs.isNotEmpty()) db.epgDao().insertPrograms(parsed.programs)
        }
    }

    private fun readText(context: Context, source: String): String? {
        val stream = openStream(context, source, gunzipIfNeeded = true) ?: return null
        return stream.use { it.bufferedReader(Charsets.UTF_8).readText() }
    }

    private fun openStream(context: Context, source: String, gunzipIfNeeded: Boolean): InputStream? {
        val raw: InputStream? = when {
            source.startsWith("http://", true) || source.startsWith("https://", true) -> {
                val client = HttpClientFactory.get(context)
                val req = Request.Builder().url(source)
                    .header("User-Agent", "TVTron/1.0")
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) { resp.close(); null } else resp.body?.byteStream()
            }
            source.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(source))
            source.startsWith("file://") -> context.contentResolver.openInputStream(Uri.parse(source))
            else -> null
        } ?: return null
        return if (gunzipIfNeeded && (source.endsWith(".gz", true) || source.endsWith(".gzip", true))) {
            GZIPInputStream(raw)
        } else raw
    }
}
