package com.tvtron.player.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.tvtron.player.R
import com.tvtron.player.data.AppDatabase
import com.tvtron.player.data.Channel
import com.tvtron.player.data.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChannelEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PRESELECT_PLAYLIST = "preselect_playlist"
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_PREFILL_NAME = "prefill_name"
        const val EXTRA_PREFILL_STREAM = "prefill_stream"
        const val EXTRA_PREFILL_LOGO = "prefill_logo"
        const val EXTRA_PREFILL_GROUP = "prefill_group"
        const val EXTRA_PREFILL_TVG = "prefill_tvg"
        const val EXTRA_PREFILL_UA = "prefill_ua"
        const val EXTRA_PREFILL_REFERER = "prefill_referer"
    }

    private var existing: Channel? = null
    private lateinit var spinner: Spinner
    private lateinit var name: TextInputEditText
    private lateinit var stream: TextInputEditText
    private lateinit var logo: TextInputEditText
    private lateinit var group: TextInputEditText
    private lateinit var tvgId: TextInputEditText
    private lateinit var ua: TextInputEditText
    private lateinit var referer: TextInputEditText

    private var playlists: List<Playlist> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel_edit)
        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(tb)
        supportActionBar?.title = getString(R.string.add_channel)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        tb.setNavigationOnClickListener { finish() }

        spinner = findViewById(R.id.edit_target_playlist)
        name = findViewById(R.id.edit_channel_name)
        stream = findViewById(R.id.edit_channel_stream)
        logo = findViewById(R.id.edit_channel_logo)
        group = findViewById(R.id.edit_channel_group)
        tvgId = findViewById(R.id.edit_channel_tvgid)
        ua = findViewById(R.id.edit_channel_ua)
        referer = findViewById(R.id.edit_channel_referer)

        findViewById<Button>(R.id.btn_save_channel).setOnClickListener { save() }

        loadPlaylists()
    }

    private fun loadPlaylists() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@ChannelEditActivity).playlistDao().getAll()
            }
            playlists = list
            if (list.isEmpty()) {
                Toast.makeText(this@ChannelEditActivity, R.string.add_channel_no_playlist, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            spinner.adapter = ArrayAdapter(this@ChannelEditActivity, android.R.layout.simple_spinner_item, list.map { it.name }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            val editId = intent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
            if (editId > 0) {
                val ch = withContext(Dispatchers.IO) {
                    AppDatabase.getInstance(this@ChannelEditActivity).channelDao().getById(editId)
                }
                if (ch == null) { finish(); return@launch }
                existing = ch
                supportActionBar?.title = getString(R.string.edit_channel)
                name.setText(ch.name)
                stream.setText(ch.streamUrl)
                logo.setText(ch.logo)
                group.setText(ch.groupTitle)
                tvgId.setText(ch.tvgId)
                ua.setText(ch.userAgent)
                referer.setText(ch.referer)
                val idx = list.indexOfFirst { it.id == ch.playlistId }.takeIf { it >= 0 } ?: 0
                spinner.setSelection(idx, false)
            } else {
                val pre = intent.getLongExtra(EXTRA_PRESELECT_PLAYLIST, -1L)
                val idx = list.indexOfFirst { it.id == pre }.takeIf { it >= 0 } ?: 0
                spinner.setSelection(idx, false)
                applyDeepLinkOrPrefill()
            }
        }
    }

    /** Prefill from a tvtron://channel deep link OR EXTRA_PREFILL_* extras (from in-app scanner). */
    private fun applyDeepLinkOrPrefill() {
        val data = intent.data
        if (data != null && data.scheme.equals("tvtron", true) && data.host.equals("channel", true)) {
            data.getQueryParameter("n")?.takeIf { it.isNotBlank() }?.let { name.setText(it) }
            data.getQueryParameter("u")?.takeIf { it.isNotBlank() }?.let { stream.setText(it) }
            data.getQueryParameter("l")?.takeIf { it.isNotBlank() }?.let { logo.setText(it) }
            data.getQueryParameter("g")?.takeIf { it.isNotBlank() }?.let { group.setText(it) }
            data.getQueryParameter("t")?.takeIf { it.isNotBlank() }?.let { tvgId.setText(it) }
            data.getQueryParameter("ua")?.takeIf { it.isNotBlank() }?.let { ua.setText(it) }
            data.getQueryParameter("r")?.takeIf { it.isNotBlank() }?.let { referer.setText(it) }
            return
        }
        intent.getStringExtra(EXTRA_PREFILL_NAME)?.takeIf { it.isNotBlank() }?.let { name.setText(it) }
        intent.getStringExtra(EXTRA_PREFILL_STREAM)?.takeIf { it.isNotBlank() }?.let { stream.setText(it) }
        intent.getStringExtra(EXTRA_PREFILL_LOGO)?.takeIf { it.isNotBlank() }?.let { logo.setText(it) }
        intent.getStringExtra(EXTRA_PREFILL_GROUP)?.takeIf { it.isNotBlank() }?.let { group.setText(it) }
        intent.getStringExtra(EXTRA_PREFILL_TVG)?.takeIf { it.isNotBlank() }?.let { tvgId.setText(it) }
        intent.getStringExtra(EXTRA_PREFILL_UA)?.takeIf { it.isNotBlank() }?.let { ua.setText(it) }
        intent.getStringExtra(EXTRA_PREFILL_REFERER)?.takeIf { it.isNotBlank() }?.let { referer.setText(it) }
    }

    private fun save() {
        val n = name.text?.toString()?.trim().orEmpty()
        val s = stream.text?.toString()?.trim().orEmpty()
        if (n.isEmpty() || s.isEmpty()) {
            Toast.makeText(this, R.string.channel_required_fields, Toast.LENGTH_SHORT).show()
            return
        }
        val target = playlists.getOrNull(spinner.selectedItemPosition) ?: return
        val base = existing

        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@ChannelEditActivity)
            withContext(Dispatchers.IO) {
                if (base == null) {
                    val nextSort = db.channelDao().maxSortIndex(target.id) + 1
                    db.channelDao().insert(Channel(
                        playlistId = target.id,
                        tvgId = tvgId.text?.toString()?.trim().orEmpty(),
                        name = n,
                        logo = logo.text?.toString()?.trim().orEmpty(),
                        groupTitle = group.text?.toString()?.trim().orEmpty(),
                        streamUrl = s,
                        userAgent = ua.text?.toString()?.trim().orEmpty(),
                        referer = referer.text?.toString()?.trim().orEmpty(),
                        sortIndex = nextSort
                    ))
                } else {
                    val sort = if (base.playlistId == target.id) base.sortIndex
                               else db.channelDao().maxSortIndex(target.id) + 1
                    db.channelDao().update(base.copy(
                        playlistId = target.id,
                        tvgId = tvgId.text?.toString()?.trim().orEmpty(),
                        name = n,
                        logo = logo.text?.toString()?.trim().orEmpty(),
                        groupTitle = group.text?.toString()?.trim().orEmpty(),
                        streamUrl = s,
                        userAgent = ua.text?.toString()?.trim().orEmpty(),
                        referer = referer.text?.toString()?.trim().orEmpty(),
                        sortIndex = sort
                    ))
                }
            }
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
