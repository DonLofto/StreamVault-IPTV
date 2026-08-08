package com.streamvault.player.playback

import com.streamvault.player.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordination signal between the primary player and the live-timeshift capture loop.
 *
 * When the primary player is buffer-stressed (e.g. rebuffering), the timeshift capture must defer
 * its separate full network read so it never competes with the player's own requests for provider
 * bandwidth / connection allowance.
 */
internal class LiveTimeshiftPlaybackGate {
    private val _stressed = MutableStateFlow(false)
    val stressed: StateFlow<Boolean> = _stressed.asStateFlow()

    fun setStressed(value: Boolean) {
        _stressed.value = value
    }
}

internal fun isPlaybackStressedForTimeshift(playbackState: PlaybackState): Boolean =
    playbackState == PlaybackState.BUFFERING