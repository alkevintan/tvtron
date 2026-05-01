package com.tvtron.player.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvtron.player.data.AppDatabase
import com.tvtron.player.data.Channel
import com.tvtron.player.data.Playlist
import com.tvtron.player.util.PlaylistRepository
import com.tvtron.player.util.SettingsManager
import com.tvtron.player.worker.RefreshScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

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
        if (pid <= 0L) flowOf(emptyList()) else db.channelDao().observeForPlaylist(pid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val categories: StateFlow<List<String>> = _currentPlaylistId.flatMapLatest { pid ->
        if (pid <= 0L) flowOf(emptyList()) else db.channelDao().observeCategories(pid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val favoriteIds: StateFlow<Set<Long>> = _currentPlaylistId
        .flatMapLatest { pid ->
            if (pid <= 0L) flowOf(emptyList()) else db.favoriteDao().observeIds(pid)
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
            val xmltvIds = if (pid > 0) db.epgDao().searchProgramTitlesXmltvIds(pid, q).toSet() else emptySet()
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
                if (_currentPlaylistId.value <= 0L && list.isNotEmpty()) {
                    setCurrentPlaylist(list.first().id)
                }
                triggerOnLaunchRefresh(list)
            }
        }
    }

    private var refreshDoneOnce = false
    private fun triggerOnLaunchRefresh(list: List<Playlist>) {
        if (refreshDoneOnce) return
        refreshDoneOnce = true
        list.filter { it.autoRefresh == com.tvtron.player.data.AutoRefreshMode.ON_LAUNCH }
            .forEach { p ->
                viewModelScope.launch {
                    runCatching { PlaylistRepository.refresh(getApplication(), p) }
                }
            }
        // also re-apply scheduled jobs
        RefreshScheduler.applyAll(getApplication(), list)
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
        if (pid <= 0L) return
        viewModelScope.launch {
            db.playlistDao().getById(pid)?.let {
                runCatching { PlaylistRepository.refresh(getApplication(), it) }
            }
        }
    }
}
