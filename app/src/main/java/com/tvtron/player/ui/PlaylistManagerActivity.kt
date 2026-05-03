package com.tvtron.player.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import com.tvtron.player.util.QrImageDecoder
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

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { QrImageDecoder.decode(this@PlaylistManagerActivity, uri) }
            if (text == null) {
                Toast.makeText(this@PlaylistManagerActivity, R.string.qr_image_no_code, Toast.LENGTH_SHORT).show()
            } else {
                handleScan(text)
            }
        }
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
        R.id.action_scan_qr -> { promptQrSource(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun promptQrSource() {
        val labels = arrayOf(getString(R.string.qr_source_camera), getString(R.string.qr_source_gallery))
        AlertDialog.Builder(this)
            .setTitle(R.string.scan_qr)
            .setItems(labels) { _, which ->
                if (which == 0) startScan() else pickImage()
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

    private fun pickImage() {
        pickImageLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun handleScan(text: String) {
        val payload = com.tvtron.player.util.TvtronUri.parse(text) as? com.tvtron.player.util.TvtronUri.Payload.PlaylistPayload
        if (payload == null) {
            Toast.makeText(this, R.string.scan_qr_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, PlaylistEditActivity::class.java).apply {
            putExtra(PlaylistEditActivity.EXTRA_PREFILL_NAME, payload.name)
            putExtra(PlaylistEditActivity.EXTRA_PREFILL_SOURCE, payload.source)
            putExtra(PlaylistEditActivity.EXTRA_PREFILL_EPG, payload.epg)
        })
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
