package com.tvtron.player.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.tvtron.player.R
import com.tvtron.player.data.AppDatabase
import com.tvtron.player.data.Channel
import com.tvtron.player.data.Favorite
import com.tvtron.player.service.PlaybackService
import com.tvtron.player.util.SettingsManager
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        private const val STATE_SKIN_OVERRIDE = "skin_override"
    }

    private lateinit var playerView: PlayerView
    private lateinit var bufferProgress: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var epgNow: TextView
    private lateinit var epgNext: TextView
    private var topOverlay: View? = null
    private var bottomOverlay: View? = null
    private lateinit var volumeOsd: RetroVolumeBarView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnFav: ImageButton
    private lateinit var btnAspect: View
    private lateinit var btnAudio: View
    private lateinit var btnSubs: View
    private lateinit var channelNumber: TextView

    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { setOverlayVisible(false) }

    private var channelId: Long = -1L
    private var currentChannel: Channel? = null
    private var siblings: List<Channel> = emptyList()

    private var aspectMode: SettingsManager.AspectMode = SettingsManager.AspectMode.FIT
    private var service: PlaybackService? = null
    private var trinitronSkin: Boolean = false
    private var lastOrientation: Int = 0
    private var previousChannelId: Long = -1L
    private var skinOverride: Boolean = false  // temp full-screen exit from skin
    private var recreating: Boolean = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? PlaybackService.LocalBinder)?.getService() ?: return
            service = s
            attachPlayer()
            loadAndPlay()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        skinOverride = savedInstanceState?.getBoolean(STATE_SKIN_OVERRIDE, false) ?: false
        val skinSetting = SettingsManager.isTrinitronSkin(this)
        trinitronSkin = skinSetting && !skinOverride
        if (trinitronSkin) {
            // Skin allows rotation; layout-land variant compacts the remote beside the screen.
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
        }
        setContentView(if (trinitronSkin) R.layout.activity_player_trinitron else R.layout.activity_player)
        lastOrientation = resources.configuration.orientation
        if (!trinitronSkin) hideSystemBars()

        playerView = findViewById(R.id.player_view)
        bufferProgress = findViewById(R.id.buffer_progress)
        titleView = findViewById(R.id.channel_title)
        epgNow = findViewById(R.id.epg_now)
        epgNext = findViewById(R.id.epg_next)
        topOverlay = findViewById(R.id.top_overlay)
        bottomOverlay = findViewById(R.id.bottom_overlay)
        volumeOsd = findViewById(R.id.volume_osd)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnFav = findViewById(R.id.btn_fav)
        btnAspect = findViewById(R.id.btn_aspect)
        btnAudio = findViewById(R.id.btn_audio)
        btnSubs = findViewById(R.id.btn_subs)
        channelNumber = findViewById(R.id.channel_number)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
        aspectMode = SettingsManager.getDefaultAspect(this)
        applyAspect()

        wireGestures()
        wireControls()

        bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
        startService(Intent(this, PlaybackService::class.java))
    }

    override fun onStart() {
        super.onStart()
        if (!trinitronSkin) hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (recreating) return
        if (trinitronSkin && newConfig.orientation != lastOrientation) {
            lastOrientation = newConfig.orientation
            recreating = true
            window.decorView.post { recreate() }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean(STATE_SKIN_OVERRIDE, skinOverride)
    }

    override fun onDestroy() {
        runCatching { unbindService(connection) }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun attachPlayer() {
        playerView.player = service?.exoPlayer
    }

    private fun loadAndPlay() {
        if (channelId <= 0L) { finish(); return }
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@PlayerActivity)
            val ch = db.channelDao().getById(channelId) ?: run { finish(); return@launch }
            currentChannel = ch
            siblings = db.channelDao().getForPlaylist(ch.playlistId)
            titleView.text = ch.name
            updateChannelNumber()
            updateFavIcon()
            loadEpg(ch)
            service?.play(ch)
            observePlayer()
            setOverlayVisible(true)
            SettingsManager.setLastChannelId(this@PlayerActivity, ch.id)
        }
    }

    private fun updateChannelNumber() {
        val ch = currentChannel ?: return
        val idx = siblings.indexOfFirst { it.id == ch.id }
        channelNumber.text = if (idx >= 0) "%03d".format(idx + 1) else ""
        channelNumber.visibility = if (SettingsManager.isShowChannelNumber(this)) View.VISIBLE else View.GONE
    }

    private suspend fun loadEpg(ch: Channel) {
        if (ch.tvgId.isBlank()) {
            epgNow.text = ""
            epgNext.text = ""
            return
        }
        val db = AppDatabase.getInstance(this)
        val now = System.currentTimeMillis()
        val cur = db.epgDao().getCurrent(ch.playlistId, ch.tvgId, now)
        val up = db.epgDao().getUpcoming(ch.playlistId, ch.tvgId, now, 1).firstOrNull()
        epgNow.text = cur?.let { "Now: ${it.title}" }.orEmpty()
        epgNext.text = up?.let { "Next: ${it.title}" }.orEmpty()
    }

    private fun observePlayer() {
        val s = service ?: return
        lifecycleScope.launch {
            s.isBuffering.collect { buf ->
                bufferProgress.visibility = if (buf) View.VISIBLE else View.GONE
                updateKeepScreenOn()
            }
        }
        lifecycleScope.launch {
            s.isPlaying.collect { p ->
                btnPlayPause.setImageResource(if (p) R.drawable.ic_pause else R.drawable.ic_play)
                updateKeepScreenOn()
            }
        }
    }

    private fun wireControls() {
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        btnPlayPause.setOnClickListener {
            val s = service ?: return@setOnClickListener
            if (s.isPlaying.value) s.pause() else s.exoPlayer?.play()
        }
        findViewById<View?>(R.id.btn_prev)?.setOnClickListener { zap(-1) }
        findViewById<View?>(R.id.btn_next)?.setOnClickListener { zap(+1) }
        findViewById<ImageButton>(R.id.btn_vol_down).setOnClickListener { adjustVolume(-1); setOverlayVisible(true) }
        findViewById<ImageButton>(R.id.btn_vol_up).setOnClickListener { adjustVolume(+1); setOverlayVisible(true) }
        // Trinitron bezel buttons (only present in skin layout)
        findViewById<View?>(R.id.btn_power)?.setOnClickListener { powerOff() }
        findViewById<View?>(R.id.btn_guide)?.setOnClickListener { showGuide() }
        findViewById<View?>(R.id.btn_ch_minus)?.setOnClickListener { zap(-1) }
        findViewById<View?>(R.id.btn_ch_plus)?.setOnClickListener { zap(+1) }
        findViewById<View?>(R.id.btn_last)?.setOnClickListener { lastChannel() }
        // Skin fullscreen toggle: present in both skin layouts and (hidden by default) in the
        // non-skin layout's overlay. We only expose it when the user actually has the skin
        // setting on, since otherwise the non-skin layout is the natural fullscreen.
        val skinSetting = SettingsManager.isTrinitronSkin(this)
        findViewById<View?>(R.id.btn_skin_fullscreen)?.let { btn ->
            btn.visibility = if (skinSetting) View.VISIBLE else View.GONE
            btn.setOnClickListener {
                if (recreating) return@setOnClickListener
                recreating = true
                skinOverride = !skinOverride
                // Defer to next loop tick so the click handler unwinds before activity is torn down.
                btn.post { recreate() }
            }
        }
        findViewById<View?>(R.id.btn_panel_vol_minus)?.setOnClickListener { adjustVolume(-1) }
        findViewById<View?>(R.id.btn_panel_vol_plus)?.setOnClickListener { adjustVolume(+1) }
        // Trinitron remote keypad (only in skin layouts)
        listOf(
            R.id.btn_num_0 to 0, R.id.btn_num_1 to 1, R.id.btn_num_2 to 2,
            R.id.btn_num_3 to 3, R.id.btn_num_4 to 4, R.id.btn_num_5 to 5,
            R.id.btn_num_6 to 6, R.id.btn_num_7 to 7, R.id.btn_num_8 to 8,
            R.id.btn_num_9 to 9
        ).forEach { (id, n) -> findViewById<View?>(id)?.setOnClickListener { onNumberPress(n) } }
        findViewById<View?>(R.id.btn_mute)?.setOnClickListener { toggleMute() }
        findViewById<View?>(R.id.btn_ok)?.setOnClickListener { commitNumber() }
        btnAspect.setOnClickListener { cycleAspect() }
        btnFav.setOnClickListener { toggleFavorite() }
        btnAudio.setOnClickListener { showTrackPopup(C.TRACK_TYPE_AUDIO, btnAudio) }
        btnSubs.setOnClickListener { showTrackPopup(C.TRACK_TYPE_TEXT, btnSubs) }
    }

    private fun cycleAspect() {
        val all = SettingsManager.AspectMode.values()
        aspectMode = all[(all.indexOf(aspectMode) + 1) % all.size]
        applyAspect()
        android.widget.Toast.makeText(this, aspectMode.label, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun applyAspect() {
        playerView.resizeMode = when (aspectMode) {
            SettingsManager.AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            SettingsManager.AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            SettingsManager.AspectMode.RATIO_16_9 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            SettingsManager.AspectMode.RATIO_4_3 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
        }
        (btnAspect as? Button)?.text = aspectMode.label
    }

    private fun showTrackPopup(trackType: Int, anchor: View) {
        val player = service?.exoPlayer ?: return
        val tracks: Tracks = player.currentTracks
        val groups = tracks.groups.filter { it.type == trackType }
        if (groups.isEmpty()) return
        val popup = PopupMenu(this, anchor)
        var idx = 0
        val mapping = mutableListOf<Pair<Tracks.Group, Int>>()
        groups.forEach { group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = format.label ?: format.language ?: "Track ${idx + 1}"
                popup.menu.add(0, idx, idx, label)
                mapping += group to i
                idx++
            }
        }
        popup.setOnMenuItemClickListener { item ->
            val (group, trackIdx) = mapping[item.itemId]
            val params = (player.trackSelectionParameters as? DefaultTrackSelector.Parameters
                ?: player.trackSelectionParameters)
                .buildUpon()
                .clearOverridesOfType(trackType)
                .setOverrideForType(
                    com.google.android.exoplayer2.trackselection.TrackSelectionOverride(group.mediaTrackGroup, trackIdx)
                )
                .build()
            player.trackSelectionParameters = params
            true
        }
        popup.show()
    }

    private fun toggleFavorite() {
        val ch = currentChannel ?: return
        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(this@PlayerActivity).favoriteDao()
            if (dao.isFavorite(ch.id)) dao.remove(ch.id)
            else dao.add(Favorite(channelId = ch.id, playlistId = ch.playlistId))
            updateFavIcon()
        }
    }

    private fun updateFavIcon() {
        val ch = currentChannel ?: return
        lifecycleScope.launch {
            val on = AppDatabase.getInstance(this@PlayerActivity).favoriteDao().isFavorite(ch.id)
            btnFav.setImageResource(if (on) R.drawable.ic_favorite else R.drawable.ic_favorite_off)
        }
    }

    private fun zap(delta: Int) {
        val ch = currentChannel ?: return
        if (siblings.isEmpty()) return
        val idx = siblings.indexOfFirst { it.id == ch.id }
        if (idx < 0) return
        val n = siblings.size
        zapTo(siblings[((idx + delta) % n + n) % n])
        setOverlayVisible(true)
    }

    private fun wireGestures() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val to = topOverlay ?: return true
                setOverlayVisible(to.visibility != View.VISIBLE)
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 120f) {
                    if (dy < 0) zap(+1) else zap(-1)
                    return true
                }
                return false
            }
        })
        playerView.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); true }
    }

    private fun setOverlayVisible(visible: Boolean) {
        // Suppress overlay entirely when skin's bezel already provides controls
        // and the user has opted to hide the on-screen overlay.
        if (trinitronSkin && SettingsManager.isHideOverlayInSkin(this) && visible) {
            topOverlay?.visibility = View.GONE
            bottomOverlay?.visibility = View.GONE
            return
        }
        val v = if (visible) View.VISIBLE else View.GONE
        topOverlay?.visibility = v
        bottomOverlay?.visibility = v
        handler.removeCallbacks(hideOverlay)
        if (visible) handler.postDelayed(hideOverlay, 3500L)
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { adjustVolume(+1); true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { adjustVolume(-1); true }
            KeyEvent.KEYCODE_DPAD_UP -> { zap(+1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { zap(-1); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun updateKeepScreenOn() {
        val s = service ?: return
        if (s.isPlaying.value || s.isBuffering.value) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun adjustVolume(delta: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val next = (cur + delta).coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        if (next > 0) mutedVolume = null
        volumeOsd.show(next, max)
    }

    // --- Mute ---
    private var mutedVolume: Int? = null
    private fun toggleMute() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (mutedVolume == null) {
            mutedVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            volumeOsd.show(0, max)
        } else {
            val restore = mutedVolume ?: 0
            mutedVolume = null
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restore, 0)
            volumeOsd.show(restore, max)
        }
    }

    // --- Number keypad ---
    private val numBuffer = StringBuilder()
    private val numCommitRunnable = Runnable { commitNumber() }

    private fun onNumberPress(n: Int) {
        if (numBuffer.length >= 3) numBuffer.clear()
        numBuffer.append(n)
        channelNumber.text = numBuffer.toString().padStart(3, '0')
        handler.removeCallbacks(numCommitRunnable)
        handler.postDelayed(numCommitRunnable, 1500L)
    }

    private fun commitNumber() {
        handler.removeCallbacks(numCommitRunnable)
        val s = numBuffer.toString()
        numBuffer.clear()
        val n = s.toIntOrNull()
        if (n == null || n < 1 || n > siblings.size) {
            updateChannelNumber()
            return
        }
        zapTo(siblings[n - 1])
    }

    private fun powerOff() {
        service?.stopPlayback()
        stopService(android.content.Intent(this, PlaybackService::class.java))
        finish()
    }

    private fun showGuide() {
        val ch = currentChannel ?: return
        if (ch.tvgId.isBlank()) {
            android.widget.Toast.makeText(this, "No EPG for this channel", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@PlayerActivity)
            val now = System.currentTimeMillis()
            val cur = db.epgDao().getCurrent(ch.playlistId, ch.tvgId, now)
            val upcoming = db.epgDao().getUpcoming(ch.playlistId, ch.tvgId, now, 10)
            val fmt = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault())
            val sb = StringBuilder()
            cur?.let { sb.append("Now: ${fmt.format(java.util.Date(it.start))} — ${it.title}\n\n") }
            upcoming.forEach { sb.append("${fmt.format(java.util.Date(it.start))} — ${it.title}\n") }
            if (sb.isEmpty()) sb.append("No upcoming programs.")
            androidx.appcompat.app.AlertDialog.Builder(this@PlayerActivity)
                .setTitle("Guide — ${ch.name}")
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun zapTo(target: Channel) {
        currentChannel?.id?.takeIf { it != target.id }?.let { previousChannelId = it }
        channelId = target.id
        currentChannel = target
        titleView.text = target.name
        updateChannelNumber()
        lifecycleScope.launch { loadEpg(target) }
        service?.play(target)
        updateFavIcon()
        SettingsManager.setLastChannelId(this, target.id)
    }

    private fun lastChannel() {
        val pid = previousChannelId
        if (pid <= 0) {
            android.widget.Toast.makeText(this, "No previous channel", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val target = siblings.firstOrNull { it.id == pid } ?: return
        zapTo(target)
    }
}
