package com.streamvault.player.timeshift

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A35 - the DASH window is filled from the live edge backwards on the first poll, which inserts at the
 * oldest end. These tests pin the ordering that makes that safe: the deque must stay chronological so
 * pruning still discards the genuinely oldest segment rather than the one just inserted.
 */
class DashWindowBackfillTest {

    private fun segment(url: String, durationMs: Long = 1_000L) =
        DefaultLiveTimeshiftManager.HlsSegmentSnapshot(
            remoteUrl = url,
            durationMs = durationMs,
            file = null,
            payload = null
        )

    private fun urlsOf(window: DefaultLiveTimeshiftManager.DashWindow) =
        window.mediaSegments().map { it.remoteUrl }

    @Test
    fun `backfilling newest first leaves the window in chronological order`() {
        val window = DefaultLiveTimeshiftManager.DashWindow(depthMs = 10_000L)

        // The poll enumerates the manifest newest first while backfilling.
        window.addMediaFirst(segment("seg-3"))
        window.addMediaFirst(segment("seg-2"))
        window.addMediaFirst(segment("seg-1"))

        assertThat(urlsOf(window)).containsExactly("seg-1", "seg-2", "seg-3").inOrder()
        assertThat(window.mediaDurationMs()).isEqualTo(3_000L)
    }

    @Test
    fun `a segment arriving after the backfill is appended, not prepended`() {
        val window = DefaultLiveTimeshiftManager.DashWindow(depthMs = 10_000L)
        window.addMediaFirst(segment("seg-2"))
        window.addMediaFirst(segment("seg-1"))

        // Later polls only ever see segments newer than everything already held.
        window.addMedia(segment("seg-3"))

        assertThat(urlsOf(window)).containsExactly("seg-1", "seg-2", "seg-3").inOrder()
    }

    @Test
    fun `pruning discards the oldest segment, never the one just inserted`() {
        val evicted = mutableListOf<String>()
        val window = DefaultLiveTimeshiftManager.DashWindow(
            depthMs = 3_000L,
            onEvict = { evicted += it.remoteUrl }
        )
        window.addMediaFirst(segment("seg-3"))
        window.addMediaFirst(segment("seg-2"))
        window.addMediaFirst(segment("seg-1"))

        // seg-0 is older than the three second retention, so it is pruned as it arrives.
        window.addMediaFirst(segment("seg-0"))

        assertThat(evicted).containsExactly("seg-0")
        assertThat(urlsOf(window)).containsExactly("seg-1", "seg-2", "seg-3").inOrder()
        assertThat(window.mediaDurationMs()).isEqualTo(3_000L)
    }

    @Test
    fun `the init segment is kept out of the media queue and its pruning`() {
        val window = DefaultLiveTimeshiftManager.DashWindow(depthMs = 1_000L)
        window.addInit(segment("init", durationMs = 0L))

        window.addMediaFirst(segment("seg-2"))
        window.addMediaFirst(segment("seg-1"))
        window.addMediaFirst(segment("seg-0"))

        assertThat(window.initSegment()?.remoteUrl).isEqualTo("init")
        // A zero duration init can never be counted against the retention window.
        assertThat(window.mediaDurationMs()).isEqualTo(1_000L)
        assertThat(window.allSegments().first().remoteUrl).isEqualTo("init")
    }
}
