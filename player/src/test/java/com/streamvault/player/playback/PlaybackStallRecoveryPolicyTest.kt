package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PlaybackState
import org.junit.Test

class PlaybackStallRecoveryPolicyTest {
    @Test
    fun `ready stalls are recovered for live transport streams`() {
        assertThat(shouldRecoverReadyStalls(ResolvedStreamType.MPEG_TS_LIVE)).isTrue()
    }

    @Test
    fun `position advancing ready stalls are not recovered for live streams`() {
        assertThat(shouldRecoverPositionAdvancingReadyStalls(ResolvedStreamType.MPEG_TS_LIVE)).isFalse()
        assertThat(shouldRecoverPositionAdvancingReadyStalls(ResolvedStreamType.HLS)).isFalse()
        assertThat(shouldRecoverPositionAdvancingReadyStalls(ResolvedStreamType.PROGRESSIVE)).isTrue()
    }

    @Test
    fun `frame silent ready stalls are recovered for live streams`() {
        assertThat(shouldRecoverFrameSilentReadyStalls(ResolvedStreamType.MPEG_TS_LIVE)).isTrue()
        assertThat(shouldRecoverFrameSilentReadyStalls(ResolvedStreamType.HLS)).isTrue()
        assertThat(shouldRecoverFrameSilentReadyStalls(ResolvedStreamType.PROGRESSIVE)).isFalse()
    }

    @Test
    fun `first live stall is absorbed and does not reconnect`() {
        assertThat(
            shouldReconnectLiveStall(
                playbackState = PlaybackState.READY,
                resolvedStreamType = ResolvedStreamType.MPEG_TS_LIVE,
                recoveryAttempt = 1
            )
        ).isFalse()
        assertThat(
            shouldReconnectLiveStall(
                playbackState = PlaybackState.BUFFERING,
                resolvedStreamType = ResolvedStreamType.HLS,
                recoveryAttempt = 1
            )
        ).isFalse()
    }

    @Test
    fun `live buffering stall reconnects on a repeated attempt`() {
        assertThat(
            shouldReconnectLiveStall(
                playbackState = PlaybackState.BUFFERING,
                resolvedStreamType = ResolvedStreamType.MPEG_TS_LIVE,
                recoveryAttempt = 2
            )
        ).isTrue()
    }

    @Test
    fun `live ready stall reconnects only when the buffer is exhausted`() {
        assertThat(
            shouldReconnectLiveStall(
                playbackState = PlaybackState.READY,
                resolvedStreamType = ResolvedStreamType.MPEG_TS_LIVE,
                recoveryAttempt = 2,
                bufferedDurationMs = 0L
            )
        ).isTrue()
        assertThat(
            shouldReconnectLiveStall(
                playbackState = PlaybackState.READY,
                resolvedStreamType = ResolvedStreamType.MPEG_TS_LIVE,
                recoveryAttempt = 2,
                bufferedDurationMs = 10_000L
            )
        ).isFalse()
    }

    @Test
    fun `vod ready stalls do not reconnect as live streams`() {
        assertThat(
            shouldReconnectLiveStall(
                playbackState = PlaybackState.READY,
                resolvedStreamType = ResolvedStreamType.PROGRESSIVE,
                recoveryAttempt = 2
            )
        ).isFalse()
    }
}
