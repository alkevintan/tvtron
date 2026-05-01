package com.tvtron.player.service

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide signal of whether the playback service is currently playing,
 * so non-bound activities (e.g. MainActivity's "Now playing" menu icon) can
 * reflect playback state without binding to PlaybackService themselves.
 */
object PlaybackState {
    val isPlaying = MutableStateFlow(false)
}
