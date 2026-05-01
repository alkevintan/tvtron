package com.tvtron.player

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
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
    private lateinit var spinner: Spinner
    private lateinit var chips: ChipGroup
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var empty: TextView
    private var favoritesMenuItem: MenuItem? = null
    private var nowPlayingMenuItem: MenuItem? = null
    private var initialRouteHandled = false

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result?.contents ?: return@registerForActivityResult
        handleScan(contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.title = getString(R.string.app_name)
        if (savedInstanceState != null) initialRouteHandled = true

        spinner = findViewById(R.id.playlist_spinner)
        chips = findViewById(R.id.category_chips)
        swipe = findViewById(R.id.swipe)
        empty = findViewById(R.id.empty_view)
        val list: RecyclerView = findViewById(R.id.channel_list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = ChannelAdapter(
            onClick = { ch -> openPlayer(ch) },
            onMenu = { ch -> showChannelMenu(ch) },
            onFavorite = { ch -> vm.toggleFavorite(ch) }
        )
        list.adapter = adapter

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
            vm.playlists.collect { list ->
                renderSpinner(list)
            }
        }
    }

    private fun renderSpinner(list: List<Playlist>) {
        if (list.isEmpty()) {
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("No playlists — add one")).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            return
        }
        // Prepend a virtual "All playlists" entry that maps to ALL_PLAYLISTS_ID.
        data class Entry(val id: Long, val label: String)
        val entries = listOf(Entry(MainViewModel.ALL_PLAYLISTS_ID, getString(R.string.all_playlists))) +
            list.map { Entry(it.id, it.name) }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, entries.map { it.label }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val curId = vm.currentPlaylistId.value
        val idx = entries.indexOfFirst { it.id == curId }.takeIf { it >= 0 } ?: 1 // default to first real playlist
        spinner.setSelection(idx, false)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                entries.getOrNull(pos)?.let {
                    if (it.id != vm.currentPlaylistId.value) vm.setCurrentPlaylist(it.id)
                }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
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

    private fun showChannelMenu(ch: Channel) {
        val items = arrayOf("Edit", "Delete", "Share QR")
        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, ChannelEditActivity::class.java)
                            .putExtra(ChannelEditActivity.EXTRA_CHANNEL_ID, ch.id)
                    )
                    1 -> confirmDeleteChannel(ch)
                    2 -> ShareChannelQrDialog.newInstance(ch).show(supportFragmentManager, "share_ch_qr")
                }
            }
            .show()
    }

    private fun startScan() {
        val opts = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.scan_qr_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        scanLauncher.launch(opts)
    }

    private fun handleScan(text: String) {
        when (val payload = TvtronUri.parse(text)) {
            is TvtronUri.Payload.PlaylistPayload -> startActivity(
                Intent(this, PlaylistEditActivity::class.java).apply {
                    putExtra(PlaylistEditActivity.EXTRA_PREFILL_NAME, payload.name)
                    putExtra(PlaylistEditActivity.EXTRA_PREFILL_SOURCE, payload.source)
                    putExtra(PlaylistEditActivity.EXTRA_PREFILL_EPG, payload.epg)
                }
            )
            is TvtronUri.Payload.ChannelPayload -> startActivity(
                Intent(this, ChannelEditActivity::class.java).apply {
                    putExtra(ChannelEditActivity.EXTRA_PRESELECT_PLAYLIST,
                        vm.currentPlaylistId.value.takeIf { it > 0L } ?: -1L)
                    putExtra(ChannelEditActivity.EXTRA_PREFILL_NAME, payload.name)
                    putExtra(ChannelEditActivity.EXTRA_PREFILL_STREAM, payload.streamUrl)
                    putExtra(ChannelEditActivity.EXTRA_PREFILL_LOGO, payload.logo)
                    putExtra(ChannelEditActivity.EXTRA_PREFILL_GROUP, payload.groupTitle)
                    putExtra(ChannelEditActivity.EXTRA_PREFILL_TVG, payload.tvgId)
                    putExtra(ChannelEditActivity.EXTRA_PREFILL_UA, payload.userAgent)
                    putExtra(ChannelEditActivity.EXTRA_PREFILL_REFERER, payload.referer)
                }
            )
            null -> android.widget.Toast.makeText(this, R.string.scan_qr_invalid, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteChannel(ch: Channel) {
        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setMessage("Delete this channel?")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.getInstance(this@MainActivity).channelDao().deleteById(ch.id)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            R.id.action_scan_qr -> { startScan(); true }
            R.id.action_playlists -> { startActivity(Intent(this, PlaylistManagerActivity::class.java)); true }
            R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            R.id.action_check_update -> { manualCheckUpdate(); true }
            R.id.action_about -> { showAbout(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun manualCheckUpdate() {
        android.widget.Toast.makeText(this, "Checking…", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) {
                runCatching { UpdateChecker.fetchLatest(this@MainActivity) }.getOrNull()
            }
            SettingsManager.setLastUpdateCheck(this@MainActivity, System.currentTimeMillis())
            if (release == null) {
                android.widget.Toast.makeText(this@MainActivity, "No release found", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val current = packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_META_DATA).versionName ?: "0.0.0"
            if (!UpdateChecker.isNewer(release, current)) {
                android.widget.Toast.makeText(this@MainActivity, "Up to date ($current)", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                UpdateDialog.show(this@MainActivity, release)
            }
        }
    }

    private fun showAbout() {
        val v = packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_META_DATA).versionName ?: "0.0.0"
        val owner = getString(R.string.update_github_owner)
        val repo = getString(R.string.update_github_repo)
        val msg = buildString {
            append("TVTron ").append(v).append("\n\n")
            append("Sideload-only Android IPTV player.\n\n")
            if (owner.isNotBlank() && repo.isNotBlank()) append("https://github.com/$owner/$repo")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
