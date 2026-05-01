package com.tvtron.player.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.tvtron.player.R
import com.tvtron.player.data.AppDatabase
import com.tvtron.player.data.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistManagerActivity : AppCompatActivity() {

    private lateinit var adapter: PlaylistAdapter
    private lateinit var empty: View

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
            onDelete = { p -> confirmDelete(p) }
        )
        rv.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener {
            startActivity(Intent(this, PlaylistEditActivity::class.java))
        }

        observe()
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
