package com.tvtron.player.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.tvtron.player.R
import com.tvtron.player.data.AppDatabase
import com.tvtron.player.data.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistManagerActivity : AppCompatActivity() {

    private lateinit var adapter: PlaylistAdapter
    private lateinit var empty: View

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result?.contents ?: return@registerForActivityResult
        handleScan(contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_manager)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val rv: RecyclerView = findViewById(R.id.playlist_list)
        empty = findViewById(R.id.empty_view)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = PlaylistAdapter(
            onClick = { p ->
                startActivity(Intent(this, PlaylistEditActivity::class.java)
                    .putExtra(PlaylistEditActivity.EXTRA_PLAYLIST_ID, p.id))
            },
            onDelete = { p -> confirmDelete(p) },
            onShare = { p ->
                ShareQrDialog.newInstance(p).show(supportFragmentManager, "share_qr")
            }
        )
        rv.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener {
            startActivity(Intent(this, PlaylistEditActivity::class.java))
        }

        observe()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_playlist_manager, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_scan_qr -> { startScan(); true }
        else -> super.onOptionsItemSelected(item)
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
        val (name, url, epg) = parsePlaylistPayload(text) ?: run {
            Toast.makeText(this, R.string.scan_qr_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, PlaylistEditActivity::class.java).apply {
            putExtra(PlaylistEditActivity.EXTRA_PREFILL_NAME, name)
            putExtra(PlaylistEditActivity.EXTRA_PREFILL_SOURCE, url)
            putExtra(PlaylistEditActivity.EXTRA_PREFILL_EPG, epg)
        })
    }

    /** Accepts both the new tvtron://playlist?n=&u=&e= URI and the legacy `TVTRON|name|url|epg` format. */
    private fun parsePlaylistPayload(text: String): Triple<String, String, String>? {
        val trimmed = text.trim()
        if (trimmed.startsWith("tvtron://", ignoreCase = true)) {
            val uri = runCatching { android.net.Uri.parse(trimmed) }.getOrNull() ?: return null
            if (!uri.host.equals("playlist", true)) return null
            val u = uri.getQueryParameter("u").orEmpty()
            if (u.isBlank()) return null
            return Triple(uri.getQueryParameter("n").orEmpty(), u, uri.getQueryParameter("e").orEmpty())
        }
        val parts = trimmed.split('|')
        if (parts.size >= 3 && parts[0] == "TVTRON") {
            return Triple(parts.getOrNull(1).orEmpty(), parts.getOrNull(2).orEmpty(), parts.getOrNull(3).orEmpty())
        }
        return null
    }

    private fun observe() {
        lifecycleScope.launch {
            AppDatabase.getInstance(this@PlaylistManagerActivity)
                .playlistDao().observeAll().collect { list ->
                    adapter.submitList(list)
                    empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
        }
    }

    private fun confirmDelete(p: Playlist) {
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setMessage("Delete this playlist?")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.getInstance(this@PlaylistManagerActivity).playlistDao().delete(p)
                    }
                    com.tvtron.player.worker.RefreshScheduler.cancel(this@PlaylistManagerActivity, p.id)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
