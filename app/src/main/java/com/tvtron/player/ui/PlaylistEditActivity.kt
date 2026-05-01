package com.tvtron.player.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.tvtron.player.R
import com.tvtron.player.data.AppDatabase
import com.tvtron.player.data.AutoRefreshMode
import com.tvtron.player.data.Playlist
import com.tvtron.player.util.PlaylistRepository
import com.tvtron.player.worker.RefreshScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
        const val EXTRA_PREFILL_NAME = "prefill_name"
        const val EXTRA_PREFILL_SOURCE = "prefill_source"
        const val EXTRA_PREFILL_EPG = "prefill_epg"
    }

    private var existing: Playlist? = null
    private lateinit var name: TextInputEditText
    private lateinit var source: TextInputEditText
    private lateinit var epg: TextInputEditText
    private lateinit var hours: TextInputEditText
    private lateinit var group: RadioGroup

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) { }
            source.setText(uri.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_edit)
        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(tb)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        name = findViewById(R.id.edit_name)
        source = findViewById(R.id.edit_source)
        epg = findViewById(R.id.edit_epg)
        hours = findViewById(R.id.edit_hours)
        group = findViewById(R.id.refresh_group)

        findViewById<Button>(R.id.btn_pick_file).setOnClickListener {
            pickFile.launch(arrayOf("audio/x-mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl", "*/*"))
        }
        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        findViewById<Button>(R.id.btn_refresh_now).setOnClickListener { saveThenRefresh() }

        val pid = intent.getLongExtra(EXTRA_PLAYLIST_ID, -1L)
        if (pid > 0) {
            supportActionBar?.title = getString(R.string.edit_playlist)
            lifecycleScope.launch {
                val p = withContext(Dispatchers.IO) {
                    AppDatabase.getInstance(this@PlaylistEditActivity).playlistDao().getById(pid)
                } ?: return@launch
                existing = p
                name.setText(p.name)
                source.setText(p.source)
                epg.setText(p.epgUrl)
                hours.setText(p.scheduleHours.toString())
                when (p.autoRefresh) {
                    AutoRefreshMode.OFF -> group.check(R.id.refresh_off)
                    AutoRefreshMode.ON_LAUNCH -> group.check(R.id.refresh_launch)
                    AutoRefreshMode.SCHEDULED -> group.check(R.id.refresh_scheduled)
                }
            }
        } else {
            supportActionBar?.title = getString(R.string.add_playlist)
            group.check(R.id.refresh_launch)
            hours.setText("24")
            applyDeepLinkOrExtras()
        }
    }

    /**
     * Two pre-fill paths:
     *   - tvtron://playlist?n=..&u=..&e=.. when launched as ACTION_VIEW (deep link)
     *   - EXTRA_PREFILL_* when launched in-app from a scanned QR
     */
    private fun applyDeepLinkOrExtras() {
        val data = intent.data
        if (data != null && data.scheme.equals("tvtron", true) && data.host.equals("playlist", true)) {
            data.getQueryParameter("n")?.takeIf { it.isNotBlank() }?.let { name.setText(it) }
            data.getQueryParameter("u")?.takeIf { it.isNotBlank() }?.let { source.setText(it) }
            data.getQueryParameter("e")?.takeIf { it.isNotBlank() }?.let { epg.setText(it) }
            return
        }
        intent.getStringExtra(EXTRA_PREFILL_NAME)?.takeIf { it.isNotBlank() }?.let { name.setText(it) }
        intent.getStringExtra(EXTRA_PREFILL_SOURCE)?.takeIf { it.isNotBlank() }?.let { source.setText(it) }
        intent.getStringExtra(EXTRA_PREFILL_EPG)?.takeIf { it.isNotBlank() }?.let { epg.setText(it) }
    }

    private fun build(): Playlist? {
        val n = name.text?.toString()?.trim().orEmpty()
        val s = source.text?.toString()?.trim().orEmpty()
        if (n.isEmpty() || s.isEmpty()) return null
        val mode = when (group.checkedRadioButtonId) {
            R.id.refresh_off -> AutoRefreshMode.OFF
            R.id.refresh_scheduled -> AutoRefreshMode.SCHEDULED
            else -> AutoRefreshMode.ON_LAUNCH
        }
        val h = hours.text?.toString()?.toIntOrNull() ?: 24
        val base = existing
        return if (base == null) {
            Playlist(name = n, source = s, epgUrl = epg.text?.toString()?.trim().orEmpty(),
                autoRefresh = mode, scheduleHours = h)
        } else {
            base.copy(name = n, source = s, epgUrl = epg.text?.toString()?.trim().orEmpty(),
                autoRefresh = mode, scheduleHours = h)
        }
    }

    private fun save() {
        val p = build() ?: return
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val dao = AppDatabase.getInstance(this@PlaylistEditActivity).playlistDao()
                if (p.id == 0L) {
                    val id = dao.insert(p)
                    p.copy(id = id)
                } else {
                    dao.update(p); p
                }
            }
            RefreshScheduler.apply(this@PlaylistEditActivity, saved)
            finish()
        }
    }

    private fun saveThenRefresh() {
        val p = build() ?: return
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val dao = AppDatabase.getInstance(this@PlaylistEditActivity).playlistDao()
                if (p.id == 0L) {
                    val id = dao.insert(p)
                    p.copy(id = id)
                } else {
                    dao.update(p); p
                }
            }
            RefreshScheduler.apply(this@PlaylistEditActivity, saved)
            withContext(Dispatchers.IO) {
                runCatching { PlaylistRepository.refresh(this@PlaylistEditActivity, saved) }
            }
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
