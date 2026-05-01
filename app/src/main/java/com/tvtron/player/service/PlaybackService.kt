package com.tvtron.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.util.Util
import com.tvtron.player.MainActivity
import com.tvtron.player.R
import com.tvtron.player.data.Channel
import com.tvtron.player.util.HttpClientFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PlaybackService : LifecycleService() {

    private val binder = LocalBinder()
    var exoPlayer: ExoPlayer? = null
        private set

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var wakeLock: PowerManager.WakeLock

    private val _currentChannel = MutableStateFlow<Channel?>(null)
    val currentChannel: StateFlow<Channel?> = _currentChannel

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    companion object {
        const val CHANNEL_ID = "tvtron_playback"
        const val NOTIFICATION_ID = 101
        const val ACTION_PLAY = "com.tvtron.player.PLAY"
        const val ACTION_PAUSE = "com.tvtron.player.PAUSE"
        const val ACTION_STOP = "com.tvtron.player.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initWakeLock()
        initMediaSession()
        initPlayer()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PLAY -> exoPlayer?.play()
            ACTION_PAUSE -> exoPlayer?.pause()
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    private fun initPlayer() {
        // Decoder fallback lets ExoPlayer try lower HLS variants and alternate decoders
        // when a chosen codec rejects the stream (common on older Android with software-only H.264).
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(buildMediaSourceFactory("", ""))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exoPlayer?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                PlaybackState.isPlaying.value = isPlaying
                updatePlaybackState()
                updateNotification()
            }
            override fun onPlaybackStateChanged(state: Int) {
                _isBuffering.value = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) _errorMessage.value = null
                updatePlaybackState()
            }
            override fun onPlayerError(error: PlaybackException) {
                _errorMessage.value = error.message ?: "Playback error"
            }
        })
    }

    private fun buildHttpFactory(userAgent: String, referer: String): HttpDataSource.Factory {
        val ua = userAgent.ifBlank { "TVTron/1.0" }
        val factory = OkHttpDataSource.Factory(HttpClientFactory.get(this))
            .setUserAgent(ua)
        if (referer.isNotBlank()) {
            factory.setDefaultRequestProperties(mapOf("Referer" to referer))
        }
        return factory
    }

    private fun buildMediaSourceFactory(userAgent: String, referer: String): DefaultMediaSourceFactory {
        val httpFactory = buildHttpFactory(userAgent, referer)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        return DefaultMediaSourceFactory(dataSourceFactory)
    }

    fun play(channel: Channel) {
        _currentChannel.value = channel
        _errorMessage.value = null
        val uri = android.net.Uri.parse(channel.streamUrl)
        val mediaItem = MediaItem.fromUri(uri)
        val source = createMediaSource(channel, mediaItem)
        exoPlayer?.apply {
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
        updateMetadata(channel)
        startForegroundCompat()
        acquireWakeLock()
    }

    private fun createMediaSource(channel: Channel, mediaItem: MediaItem): MediaSource {
        val httpFactory = buildHttpFactory(channel.userAgent, channel.referer)
        val dataFactory = DefaultDataSource.Factory(this, httpFactory)
        val uri = mediaItem.localConfiguration!!.uri
        val type = Util.inferContentType(uri)
        return when (type) {
            C.CONTENT_TYPE_HLS -> HlsMediaSource.Factory(dataFactory).createMediaSource(mediaItem)
            C.CONTENT_TYPE_DASH -> DashMediaSource.Factory(dataFactory).createMediaSource(mediaItem)
            C.CONTENT_TYPE_SS -> SsMediaSource.Factory(dataFactory).createMediaSource(mediaItem)
            C.CONTENT_TYPE_RTSP -> RtspMediaSource.Factory().createMediaSource(mediaItem)
            else -> {
                // CONTENT_TYPE_OTHER: extension-less or token-based IPTV URLs.
                // 90%+ of HTTP IPTV is HLS; default to HLS for http(s), progressive otherwise.
                val s = channel.streamUrl.lowercase()
                if (s.startsWith("http://") || s.startsWith("https://")) {
                    HlsMediaSource.Factory(dataFactory).createMediaSource(mediaItem)
                } else {
                    ProgressiveMediaSource.Factory(dataFactory).createMediaSource(mediaItem)
                }
            }
        }
    }

    fun pause() { exoPlayer?.pause() }

    fun stopPlayback() {
        exoPlayer?.stop()
        _isPlaying.value = false
        _isBuffering.value = false
        PlaybackState.isPlaying.value = false
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun initWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TVTron::WakeLock")
        wakeLock.setReferenceCounted(false)
    }

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) wakeLock.acquire(4 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "TVTron").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { exoPlayer?.play() }
                override fun onPause() { exoPlayer?.pause() }
                override fun onStop() { stopPlayback() }
            })
            isActive = true
        }
    }

    private fun updateMetadata(c: Channel) {
        val md = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, c.name)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, c.groupTitle)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1)
            .build()
        mediaSession.setMetadata(md)
    }

    private fun updatePlaybackState() {
        val state = when {
            _isPlaying.value -> PlaybackStateCompat.STATE_PLAYING
            _isBuffering.value -> PlaybackStateCompat.STATE_BUFFERING
            _currentChannel.value != null -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_NONE
        }
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "TV Playback", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val channel = _currentChannel.value
        val playing = _isPlaying.value

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, PlaybackService::class.java).apply {
            action = if (playing) ACTION_PAUSE else ACTION_PLAY
        }
        val togglePending = PendingIntent.getService(
            this, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, PlaybackService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        val toggleText = if (playing) "Pause" else "Play"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(channel?.name ?: "TVTron")
            .setContentText(channel?.groupTitle.orEmpty())
            .setSmallIcon(R.drawable.ic_tv)
            .setContentIntent(openPending)
            .addAction(toggleIcon, toggleText, togglePending)
            .addAction(R.drawable.ic_stop, "Stop", stopPending)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopPlayback()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        exoPlayer?.release()
        exoPlayer = null
        mediaSession.release()
        releaseWakeLock()
        PlaybackState.isPlaying.value = false
        super.onDestroy()
    }
}
