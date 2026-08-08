package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PolicyAwareLoadControlTest {

    private val playerId = PlayerId("test")

    private val stableLive = PlaybackBufferPolicy(
        label = "stable-live",
        minBufferMs = 8_000,
        maxBufferMs = 30_000,
        playbackBufferMs = 1_500,
        rebufferMs = 5_000,
        targetBufferBytes = C.LENGTH_UNSET,
        prioritizeTimeOverSizeThresholds = true
    )

    private val largeLive = PlaybackBufferPolicy(
        label = "large-live",
        minBufferMs = 30_000,
        maxBufferMs = 90_000,
        playbackBufferMs = 5_000,
        rebufferMs = 15_000,
        targetBufferBytes = 64 * 1024 * 1024,
        prioritizeTimeOverSizeThresholds = true
    )

    private fun params(
        bufferedDurationMs: Long,
        rebuffering: Boolean = false,
        targetLiveOffsetUs: Long = C.TIME_UNSET,
        playbackSpeed: Float = 1f
    ) = LoadControl.Parameters(
        playerId,
        Timeline.EMPTY,
        LoadControl.EMPTY_MEDIA_PERIOD_ID,
        0L,
        bufferedDurationMs * 1000L,
        playbackSpeed,
        true,
        rebuffering,
        targetLiveOffsetUs,
        0L
    )

    private fun prepared(control: PolicyAwareLoadControl) {
        control.onPrepared(playerId)
    }

    @Test
    fun `keeps loading below the min buffer threshold`() {
        val control = PolicyAwareLoadControl(stableLive)
        prepared(control)

        assertThat(control.shouldContinueLoading(params(bufferedDurationMs = 5_000))).isTrue()
    }

    @Test
    fun `stops loading above the max buffer threshold`() {
        val control = PolicyAwareLoadControl(stableLive)
        prepared(control)

        assertThat(control.shouldContinueLoading(params(bufferedDurationMs = 120_000))).isFalse()
    }

    @Test
    fun `policy update is honored by the same instance without re-recreation`() {
        val control = PolicyAwareLoadControl(stableLive)
        prepared(control)
        // Bring the player into a steady state above the old max buffer.
        assertThat(
            control.shouldContinueLoading(params(bufferedDurationMs = 60_000))
        ).isFalse()

        control.updatePolicy(largeLive)

        // With large-live (min 30s), 20s buffered is below the new min -> start loading again
        // in place, proving the new policy is read by the *same* load control instance.
        assertThat(
            control.shouldContinueLoading(params(bufferedDurationMs = 20_000))
        ).isTrue()
    }

    @Test
    fun `loading state survives a policy update`() {
        val control = PolicyAwareLoadControl(stableLive)
        prepared(control)
        assertThat(control.shouldContinueLoading(params(bufferedDurationMs = 5_000))).isTrue()

        control.updatePolicy(largeLive)

        // Between large min (30s) and max (90s): loading state from before must persist.
        assertThat(
            control.shouldContinueLoading(params(bufferedDurationMs = 50_000))
        ).isTrue()
    }

    @Test
    fun `starts playback once the non-rebuffering playback buffer is reached`() {
        val control = PolicyAwareLoadControl(stableLive)

        assertThat(control.shouldStartPlayback(params(bufferedDurationMs = 1_000))).isFalse()
        assertThat(control.shouldStartPlayback(params(bufferedDurationMs = 2_000))).isTrue()
    }

    @Test
    fun `rebuffer threshold after policy update is honored`() {
        val control = PolicyAwareLoadControl(stableLive)
        control.updatePolicy(largeLive)

        assertThat(
            control.shouldStartPlayback(params(bufferedDurationMs = 10_000, rebuffering = true))
        ).isFalse()
        assertThat(
            control.shouldStartPlayback(params(bufferedDurationMs = 20_000, rebuffering = true))
        ).isTrue()
    }

    @Test
    fun `reports per-policy buffer decisions for live target offset`() {
        val control = PolicyAwareLoadControl(stableLive)

        // targetLiveOffsetUs caps the start-threshold at offset/2 (1s here).
        assertThat(
            control.shouldStartPlayback(
                params(bufferedDurationMs = 1_200, targetLiveOffsetUs = 2_000_000)
            )
        ).isTrue()
    }
}