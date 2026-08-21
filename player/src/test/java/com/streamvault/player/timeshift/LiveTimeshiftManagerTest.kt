package com.streamvault.player.timeshift

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StreamType
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Test

class LiveTimeshiftManagerTest {

    @Test
    fun dashPruning_keepsInitButEvictsOldMediaSegments() {
        val window = DefaultLiveTimeshiftManager.DashWindow(depthMs = 10_000)
        val init = segment(remote = "init.mp4", duration = 0L)
        val first = segment(remote = "seg-1.mp4", duration = 6_000L)
        val second = segment(remote = "seg-2.mp4", duration = 6_000L)

        window.addInit(init)
        window.addMedia(first)
        window.addMedia(second)

        assertThat(window.mediaSegments()).containsExactly(second)
        assertThat(window.initSegment()).isEqualTo(init)
        // Nothing with this small depth survives: first is evicted.
        assertThat(window.allSegments()).containsExactly(init, second)
    }

    @Test
    fun cancelledSession_doesNotPublishFailedAfterStop() {
        // After stop the session is no longer active: no error may reach FAILED state.
        assertThat(shouldPublishCaptureFailure(isActive = false, IOException("late"))).isFalse()
        assertThat(shouldPublishCaptureFailure(isActive = false, CancellationException("stopped"))).isFalse()
        // Cooperative cancellation never publishes, even for the active session.
        assertThat(shouldPublishCaptureFailure(isActive = true, CancellationException("stopped"))).isFalse()
        // Only genuine errors on the still-active session publish a failure.
        assertThat(shouldPublishCaptureFailure(isActive = true, IOException("real"))).isTrue()
    }

    @Test
    fun inferType_usesUriPathWhenUrlContainsQuery() {
        assertThat(inferTimeshiftStreamType("https://host/live.m3u8?token=x"))
            .isEqualTo(StreamType.HLS)
        assertThat(inferTimeshiftStreamType("https://host/dash.mpd?token=x"))
            .isEqualTo(StreamType.DASH)
    }

    @Test
    fun inferType_fallsBackToProgressiveForUnknownPath() {
        assertThat(inferTimeshiftStreamType("https://host/stream?token=x"))
            .isEqualTo(StreamType.PROGRESSIVE)
    }

    private fun segment(remote: String, duration: Long) =
        DefaultLiveTimeshiftManager.HlsSegmentSnapshot(
            remoteUrl = remote,
            durationMs = duration,
            file = null,
            payload = ByteArray(1)
        )
}
