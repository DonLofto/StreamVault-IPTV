package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PlaybackState
import org.junit.Test

class LiveTimeshiftPlaybackGateTest {

    @Test
    fun `buffering playback marks timeshift capture as stressed`() {
        assertThat(isPlaybackStressedForTimeshift(PlaybackState.BUFFERING)).isTrue()
    }

    @Test
    fun `steady and idle playback are not stressed`() {
        assertThat(isPlaybackStressedForTimeshift(PlaybackState.IDLE)).isFalse()
        assertThat(isPlaybackStressedForTimeshift(PlaybackState.READY)).isFalse()
        assertThat(isPlaybackStressedForTimeshift(PlaybackState.ENDED)).isFalse()
        assertThat(isPlaybackStressedForTimeshift(PlaybackState.ERROR)).isFalse()
    }

    @Test
    fun `gate defaults to unstressed and tracks updates`() {
        val gate = LiveTimeshiftPlaybackGate()
        assertThat(gate.stressed.value).isFalse()

        gate.setStressed(true)
        assertThat(gate.stressed.value).isTrue()

        gate.setStressed(false)
        assertThat(gate.stressed.value).isFalse()
    }
}