package com.tvtron.player.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

object SettingsManager {

    private const val KEY_THEME = "theme"
    private const val KEY_CURRENT_PLAYLIST = "current_playlist"
    private const val KEY_LAST_CHANNEL = "last_channel"
    private const val KEY_DEFAULT_ASPECT = "default_aspect"
    private const val KEY_TRINITRON_SKIN = "trinitron_skin"
    private const val KEY_SHOW_CHANNEL_NUMBER = "show_channel_number"
    private const val KEY_HIDE_OVERLAY_IN_SKIN = "hide_overlay_in_skin"
    private const val KEY_EPG_DAYS_BACK = "epg_days_back"
    private const val KEY_EPG_DAYS_FORWARD = "epg_days_forward"
    private const val KEY_AUTO_UPDATE_CHECK = "auto_update_check"
    private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
    private const val KEY_LAST_EPG_REFRESH = "last_epg_refresh"

    enum class Theme(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

    enum class AspectMode(val label: String) {
        FIT("Fit"), FILL("Fill"), RATIO_16_9("16:9"), RATIO_4_3("4:3")
    }

    private fun prefs(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    fun getTheme(c: Context): Theme = enumOr(prefs(c).getString(KEY_THEME, null), Theme.SYSTEM)
    fun setTheme(c: Context, t: Theme) { prefs(c).edit().putString(KEY_THEME, t.name).apply() }

    fun getCurrentPlaylistId(c: Context): Long = prefs(c).getLong(KEY_CURRENT_PLAYLIST, -1L)
    fun setCurrentPlaylistId(c: Context, id: Long) { prefs(c).edit().putLong(KEY_CURRENT_PLAYLIST, id).apply() }

    fun getLastChannelId(c: Context): Long = prefs(c).getLong(KEY_LAST_CHANNEL, -1L)
    fun setLastChannelId(c: Context, id: Long) { prefs(c).edit().putLong(KEY_LAST_CHANNEL, id).apply() }
    fun clearLastChannel(c: Context) { prefs(c).edit().remove(KEY_LAST_CHANNEL).apply() }

    fun isTrinitronSkin(c: Context): Boolean = prefs(c).getBoolean(KEY_TRINITRON_SKIN, false)
    fun setTrinitronSkin(c: Context, on: Boolean) { prefs(c).edit().putBoolean(KEY_TRINITRON_SKIN, on).apply() }

    fun isShowChannelNumber(c: Context): Boolean = prefs(c).getBoolean(KEY_SHOW_CHANNEL_NUMBER, true)
    fun setShowChannelNumber(c: Context, on: Boolean) { prefs(c).edit().putBoolean(KEY_SHOW_CHANNEL_NUMBER, on).apply() }

    fun isHideOverlayInSkin(c: Context): Boolean = prefs(c).getBoolean(KEY_HIDE_OVERLAY_IN_SKIN, false)
    fun setHideOverlayInSkin(c: Context, on: Boolean) { prefs(c).edit().putBoolean(KEY_HIDE_OVERLAY_IN_SKIN, on).apply() }

    fun getDefaultAspect(c: Context): AspectMode = enumOr(prefs(c).getString(KEY_DEFAULT_ASPECT, null), AspectMode.FIT)
    fun setDefaultAspect(c: Context, a: AspectMode) { prefs(c).edit().putString(KEY_DEFAULT_ASPECT, a.name).apply() }

    fun getEpgDaysBack(c: Context): Int = prefs(c).getInt(KEY_EPG_DAYS_BACK, 1)
    fun setEpgDaysBack(c: Context, n: Int) { prefs(c).edit().putInt(KEY_EPG_DAYS_BACK, n.coerceIn(0, 14)).apply() }

    fun getEpgDaysForward(c: Context): Int = prefs(c).getInt(KEY_EPG_DAYS_FORWARD, 7)
    fun setEpgDaysForward(c: Context, n: Int) { prefs(c).edit().putInt(KEY_EPG_DAYS_FORWARD, n.coerceIn(1, 14)).apply() }

    fun isAutoUpdateCheck(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTO_UPDATE_CHECK, true)
    fun setAutoUpdateCheck(c: Context, on: Boolean) { prefs(c).edit().putBoolean(KEY_AUTO_UPDATE_CHECK, on).apply() }

    fun getLastUpdateCheck(c: Context): Long = prefs(c).getLong(KEY_LAST_UPDATE_CHECK, 0L)
    fun setLastUpdateCheck(c: Context, ts: Long) { prefs(c).edit().putLong(KEY_LAST_UPDATE_CHECK, ts).apply() }

    /** Wall-clock timestamp of the last successful XMLTV parse + insert (any playlist). */
    fun getLastEpgRefresh(c: Context): Long = prefs(c).getLong(KEY_LAST_EPG_REFRESH, 0L)
    fun setLastEpgRefresh(c: Context, ts: Long) { prefs(c).edit().putLong(KEY_LAST_EPG_REFRESH, ts).apply() }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        runCatching { enumValueOf<E>(name ?: default.name) }.getOrDefault(default)
}
