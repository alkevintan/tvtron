package com.tvtron.player.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter fun fromAutoRefresh(m: AutoRefreshMode): String = m.name
    @TypeConverter fun toAutoRefresh(s: String): AutoRefreshMode =
        runCatching { AutoRefreshMode.valueOf(s) }.getOrDefault(AutoRefreshMode.ON_LAUNCH)
}
