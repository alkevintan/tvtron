package com.tvtron.player

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import com.google.android.material.tabs.TabLayout
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.tvtron.player.data.AppDatabase
import com.tvtron.player.data.Channel
import com.tvtron.player.data.Playlist
import com.tvtron.player.ui.ChannelAdapter
import com.tvtron.player.ui.PlayerActivity
import com.tvtron.player.ui.PlaylistEditActivity
import com.tvtron.player.ui.PlaylistManagerActivity
import com.tvtron.player.ui.SettingsActivity
import com.tvtron.player.ui.UpdateDialog
import com.tvtron.player.service.PlaybackState
import com.tvtron.player.ui.ChannelEditActivity
import com.tvtron.player.ui.ShareChannelQrDialog
import com.tvtron.player.util.TvtronUri
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.tvtron.player.util.SettingsManager
import com.tvtron.player.util.UpdateChecker
import com.tvtron.player.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val vm: MainViewModel by viewModels()
    private lateinit var adapter: ChannelAdapter
    private lateinit var tabs: TabLayout
    private lateinit var chips: ChipGroup
    private var tabEntries: List<Long> = emptyList()
    private var suppressTabCallback = false
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var empty: TextView
    private var favoritesMenuItem: MenuItem? = null
    private var nowPlayingMenuItem: MenuItem? = null
    private var initialRouteHandled = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.title = getString(R.string.app_name)
        if (savedInstanceState != null) initialRouteHandled = true

        tabs = findViewById(R.id.playlist_tabs)
        chips = findViewById(R.id.category_chips)
        swipe = findViewById(R.id.swipe)
        empty = findViewById(R.id.empty_view)
        val list: RecyclerView = findViewById(R.id.channel_list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = ChannelAdapter(
            onClick = { ch -> openPlayer(ch) },
            onMenu = { ch -> openChannelEditor(ch) },
            onFavorite = { ch -> vm.toggleFavorite(ch) },
            onShareQr = { ch -> ShareChannelQrDialog.newInstance(ch).show(supportFragmentManager, "share_ch_qr") }
        )
        list.adapter = adapter

        // Scroll-to-top FAB: visible when first visible item index > 8.
        val fabScrollTop = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fab_scroll_top)
        fabScrollTop.setOnClickListener { list.smoothScrollToPosition(0) }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val first = (rv.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0
                if (first > 8) fabScrollTop.show() else fabScrollTop.hide()
            }
        })

        swipe.setOnRefreshListener {
            vm.refreshCurrent()
            swipe.postDelayed({ swipe.isRefreshing = false }, 1500)
        }

        observePlaylists()
        observeCategories()
        observeChannels()
        observePlaybackState()
        routeOnLaunch()
        maybeAutoCheckUpdate()
    }

    private fun observePlaybackState() {
        lifecycleScope.launch {
            PlaybackState.isPlaying.collect { applyNowPlayingTint(it) }
        }
    }

    private fun applyNowPlayingTint(playing: Boolean) {
        val item = nowPlayingMenuItem ?: return
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_tv)?.mutate() ?: return
        val color = if (playing) ContextCompat.getColor(this, R.color.colorAccent)
                    else ContextCompat.getColor(this, R.color.colorOnPrimary)
        DrawableCompat.setTint(drawable, color)
        item.icon = drawable
    }

    /** Polls GitHub Releases on launch, throttled to once per 24h. Off-by-toggle. */
    private fun maybeAutoCheckUpdate() {
        if (!SettingsManager.isAutoUpdateCheck(this)) return
        val now = System.currentTimeMillis()
        val last = SettingsManager.getLastUpdateCheck(this)
        if (now - last < 24L * 60 * 60 * 1000) return
        lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) {
                runCatching { UpdateChecker.fetchLatest(this@MainActivity) }.getOrNull()
            } ?: return@launch
            SettingsManager.setLastUpdateCheck(this@MainActivity, System.currentTimeMillis())
            val current = packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_META_DATA).versionName ?: "0.0.0"
            if (UpdateChecker.isNewer(release, current)) UpdateDialog.show(this@MainActivity, release)
        }
    }

    /**
     * Decide first-launch destination once the playlist list is known:
     *  - no playlists      → modal prompts user to add one
     *  - last channel set  → jump to PlayerActivity
     *  - else              → stay on channel browser
     */
    private fun routeOnLaunch() {
        if (initialRouteHandled) return
        initialRouteHandled = true
        lifecycleScope.launch {
            val playlists = vm.playlists.first { true } // wait for first emission (initial empty list is fine)
            // Re-fetch synchronously to avoid acting on stale empty
            val list = AppDatabase.getInstance(this@MainActivity).playlistDao().getAll()
            if (list.isEmpty()) {
                promptAddPlaylist()
                return@launch
            }
            val lastId = SettingsManager.getLastChannelId(this@MainActivity)
            if (lastId > 0) {
                val ch = AppDatabase.getInstance(this@MainActivity).channelDao().getById(lastId)
                if (ch != null) {
                    startActivity(
                        Intent(this@MainActivity, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, ch.id)
                    )
                } else {
                    SettingsManager.clearLastChannel(this@MainActivity)
                }
            }
        }
    }

    private fun promptAddPlaylist() {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(R.string.no_playlists)
            .setCancelable(false)
            .setPositiveButton(R.string.add_playlist) { _, _ ->
                startActivity(Intent(this, PlaylistEditActivity::class.java))
            }
            .show()
    }

    private fun observePlaylists() {
        lifecycleScope.launch {
            vm.playlists.collect { list -> renderTabs(list) }
        }
        lifecycleScope.launch {
            vm.currentPlaylistId.collect { id -> selectTabFor(id) }
        }
    }

    private fun renderTabs(list: List<Playlist>) {
        suppressTabCallback = true
        tabs.removeAllTabs()
        tabs.clearOnTabSelectedListeners()
        tabEntries = if (list.isEmpty()) emptyList()
                     else listOf(MainViewModel.ALL_PLAYLISTS_ID) + list.map { it.id }
        val labels = if (list.isEmpty()) listOf(getString(R.string.no_playlists_short))
                     else listOf(getString(R.string.all_playlists)) + list.map { it.name }
        labels.forEach { tabs.addTab(tabs.newTab().setText(it)) }
        selectTabFor(vm.currentPlaylistId.value)
        suppressTabCallback = false
        if (list.isNotEmpty()) {
            tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (suppressTabCallback) return
                    val id = tabEntries.getOrNull(tab.position) ?: return
                    if (id != vm.currentPlaylistId.value) vm.setCurrentPlaylist(id)
                }
                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {
                    findViewById<RecyclerView>(R.id.channel_list).smoothScrollToPosition(0)
                }
            })
        }
    }

    private fun selectTabFor(id: Long) {
        if (tabEntries.isEmpty()) return
        val pos = tabEntries.indexOfFirst { it == id }.takeIf { it >= 0 } ?: 1.coerceAtMost(tabEntries.size - 1)
        if (tabs.selectedTabPosition == pos) return
        suppressTabCallback = true
        tabs.getTabAt(pos)?.select()
        suppressTabCallback = false
    }

    private fun observeCategories() {
        lifecycleScope.launch {
            vm.categories.collect { cats ->
                chips.removeAllViews()
                addChip(getString(R.string.all_categories), null, isChecked = vm.categoryFilter.value == null)
                cats.filter { it.isNotBlank() }.forEach { c ->
                    addChip(c, c, isChecked = vm.categoryFilter.value == c)
                }
            }
        }
    }

    private fun addChip(label: String, value: String?, isChecked: Boolean) {
        val chip = Chip(this).apply {
            text = label
            isCheckable = true
            this.isChecked = isChecked
            setOnClickListener { vm.setCategory(value) }
        }
        chips.addView(chip)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeChannels() {
        lifecycleScope.launch {
            vm.filteredChannels.combine(vm.favoriteIds) { ch, favs -> ch to favs }
                .collect { (channels, favs) ->
                    val items = channels.map { c ->
                        ChannelAdapter.Item(
                            channel = c,
                            isFavorite = c.id in favs,
                            nowTitle = null,
                            nextTitle = null
                        )
                    }
                    adapter.submitList(items)
                    empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    enrichEpgAsync(channels)
                }
        }
    }

    /** Lazy fill of nowTitle/nextTitle for visible channels (cheap N-query, EPG is local). */
    private fun enrichEpgAsync(channels: List<Channel>) {
        if (channels.isEmpty()) return
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@MainActivity)
            val now = System.currentTimeMillis()
            val enriched = channels.map { c ->
                val (cur, upcoming) = if (c.tvgId.isNotBlank()) {
                    val cur = db.epgDao().getCurrent(c.playlistId, c.tvgId, now)
                    val up = db.epgDao().getUpcoming(c.playlistId, c.tvgId, now, limit = 1).firstOrNull()
                    cur to up
                } else null to null
                ChannelAdapter.Item(
                    channel = c,
                    isFavorite = c.id in vm.favoriteIds.value,
                    nowTitle = cur?.title,
                    nextTitle = upcoming?.title
                )
            }
            adapter.submitList(enriched)
        }
    }

    private fun openPlayer(c: Channel) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL_ID, c.id))
    }

    private fun openChannelEditor(ch: Channel) {
        startActivity(
            Intent(this, ChannelEditActivity::class.java)
                .putExtra(ChannelEditActivity.EXTRA_CHANNEL_ID, ch.id)
        )
    }

    private fun openAddChannel() {
        val cur = vm.currentPlaylistId.value.takeIf { it > 0L } ?: -1L
        startActivity(
            Intent(this, com.tvtron.player.ui.ChannelEditActivity::class.java)
                .putExtra(com.tvtron.player.ui.ChannelEditActivity.EXTRA_PRESELECT_PLAYLIST, cur)
        )
    }

    private fun openLastChannel() {
        val id = SettingsManager.getLastChannelId(this)
        if (id <= 0) {
            android.widget.Toast.makeText(this, "No recent channel", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_CHANNEL_ID, id))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        favoritesMenuItem = menu.findItem(R.id.action_favorites)
        nowPlayingMenuItem = menu.findItem(R.id.action_now_playing)
        applyNowPlayingTint(PlaybackState.isPlaying.value)
        val searchItem = menu.findItem(R.id.action_search)
        val sv = searchItem.actionView as SearchView
        sv.queryHint = getString(R.string.search_hint)
        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean { vm.setQuery(query.orEmpty()); return true }
            override fun onQueryTextChange(newText: String?): Boolean { vm.setQuery(newText.orEmpty()); return true }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_now_playing -> { openLastChannel(); true }
            R.id.action_favorites -> {
                val on = !item.isChecked
                item.isChecked = on
                item.setIcon(if (on) R.drawable.ic_favorite else R.drawable.ic_favorite_off)
                vm.setFavoritesOnly(on)
                true
            }
            R.id.action_refresh -> { vm.refreshCurrent(); true }
            R.id.action_add_channel -> { openAddChannel(); true }
            R.id.action_playlists -> { startActivity(Intent(this, PlaylistManagerActivity::class.java)); true }
            R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            R.id.action_about -> {
                com.tvtron.player.ui.AboutDialog().show(supportFragmentManager, "about")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
