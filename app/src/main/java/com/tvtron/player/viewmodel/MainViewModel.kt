package com.tvtron.player.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvtron.player.data.AppDatabase
import com.tvtron.player.data.Channel
import com.tvtron.player.data.Playlist
import com.tvtron.player.util.LaunchRefresher
import com.tvtron.player.util.PlaylistRepository
import com.tvtron.player.util.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** Sentinel: combine channels from every playlist into one list. */
        const val ALL_PLAYLISTS_ID = 0L
    }

    private val db = AppDatabase.getInstance(app)

    val playlists: StateFlow<List<Playlist>> = db.playlistDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentPlaylistId = MutableStateFlow(SettingsManager.getCurrentPlaylistId(app))
    val currentPlaylistId: StateFlow<Long> = _currentPlaylistId

    private val _categoryFilter = MutableStateFlow<String?>(null) // null = All
    val categoryFilter: StateFlow<String?> = _categoryFilter

    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val channels: StateFlow<List<Channel>> = _currentPlaylistId.flatMapLatest { pid ->
        when {
            pid == ALL_PLAYLISTS_ID -> db.channelDao().observeAll()
            pid > 0L -> db.channelDao().observeForPlaylist(pid)
            else -> flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val categories: StateFlow<List<String>> = _currentPlaylistId.flatMapLatest { pid ->
        when {
            pid == ALL_PLAYLISTS_ID -> db.channelDao().observeAllCategories()
            pid > 0L -> db.channelDao().observeCategories(pid)
            else -> flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val favoriteIds: StateFlow<Set<Long>> = _currentPlaylistId
        .flatMapLatest { pid ->
            when {
                pid == ALL_PLAYLISTS_ID -> db.favoriteDao().observeAllIds()
                pid > 0L -> db.favoriteDao().observeIds(pid)
                else -> flowOf(emptyList())
            }
        }
        .let { upstream ->
            kotlinx.coroutines.flow.flow { upstream.collect { emit(it.toSet()) } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Channels filtered by category, favorites toggle, and search query (name OR upcoming program title). */
    val filteredChannels: StateFlow<List<Channel>> = combine(
        channels, _categoryFilter, _favoritesOnly, favoriteIds, _query
    ) { ch, cat, favOnly, favIds, q ->
        val byCat = if (cat == null) ch else ch.filter { it.groupTitle.equals(cat, ignoreCase = true) }
        val byFav = if (favOnly) byCat.filter { it.id in favIds } else byCat
        if (q.isBlank()) byFav
        else {
            val pid = _currentPlaylistId.value
            val xmltvIds = when {
                pid == ALL_PLAYLISTS_ID -> db.epgDao().searchProgramTitlesXmltvIdsAcrossAll(q).toSet()
                pid > 0L -> db.epgDao().searchProgramTitlesXmltvIds(pid, q).toSet()
                else -> emptySet()
            }
            val ql = q.lowercase()
            byFav.filter {
                it.name.lowercase().contains(ql) ||
                    (it.tvgId.isNotBlank() && it.tvgId in xmltvIds)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            playlists.collect { list ->
                val cur = _currentPlaylistId.value
                // Only auto-pick first when no explicit choice has been made yet.
                if (cur != ALL_PLAYLISTS_ID && cur <= 0L && list.isNotEmpty()) {
                    setCurrentPlaylist(list.first().id)
                }
                triggerOnLaunchRefresh(list)
            }
        }
    }

    private fun triggerOnLaunchRefresh(@Suppress("UNUSED_PARAMETER") list: List<Playlist>) {
        // Idempotent — splash already kicked this off; this is the safety net for
        // cases where MainActivity launches without going through SplashActivity
        // (e.g. tests, deep-link entry points).
        LaunchRefresher.start(getApplication())
    }

    fun setCurrentPlaylist(id: Long) {
        _currentPlaylistId.value = id
        SettingsManager.setCurrentPlaylistId(getApplication(), id)
    }

    fun setCategory(c: String?) { _categoryFilter.value = c }
    fun setFavoritesOnly(on: Boolean) { _favoritesOnly.value = on }
    fun setQuery(q: String) { _query.value = q }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            val dao = db.favoriteDao()
            if (dao.isFavorite(channel.id)) dao.remove(channel.id)
            else dao.add(com.tvtron.player.data.Favorite(channelId = channel.id, playlistId = channel.playlistId))
        }
    }

    fun refreshCurrent() {
        val pid = _currentPlaylistId.value
        viewModelScope.launch {
            when {
                pid == ALL_PLAYLISTS_ID -> db.playlistDao().getAll().forEach {
                    runCatching { PlaylistRepository.refresh(getApplication(), it) }
                }
                pid > 0L -> db.playlistDao().getById(pid)?.let {
                    runCatching { PlaylistRepository.refresh(getApplication(), it) }
                }
            }
        }
    }
}
