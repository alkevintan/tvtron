package com.tvtron.player.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AutoRefreshMode { OFF, ON_LAUNCH, SCHEDULED }

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Remote http(s) URL or local content:// uri string */
    val source: String,
    /** Optional XMLTV URL override; if blank, parser falls back to m3u url-tvg attr. */
    val epgUrl: String = "",
    val autoRefresh: AutoRefreshMode = AutoRefreshMode.ON_LAUNCH,
    val scheduleHours: Int = 24,
    val lastRefresh: Long = 0L,
    val sortIndex: Int = 0
) {
    val isRemote: Boolean get() = source.startsWith("http://", true) || source.startsWith("https://", true)
}
