package com.streamvault.player.timeshift

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A34 - the MEMORY backend retains rewind payloads on the Java heap, so its window is bounded
 * by bytes as well as by wall-clock depth. These tests pin the budget derivation; the retention
 * loop itself is covered by [LiveTimeshiftManagerTest].
 */
class LiveTimeshiftMemoryBudgetTest {

    @Test
    fun `budget is a quarter of the heap class`() {
        assertThat(memoryBackendByteBudget(512L * 1024L * 1024L))
            .isEqualTo(MAX_MEMORY_BACKEND_BYTES)
        assertThat(memoryBackendByteBudget(128L * 1024L * 1024L))
            .isEqualTo(32L * 1024L * 1024L)
    }

    @Test
    fun `budget on the 192 MB Fire TV Stick heap class stays well under the ceiling`() {
        val budget = memoryBackendByteBudget(192L * 1024L * 1024L)
        assertThat(budget).isEqualTo(48L * 1024L * 1024L)
        assertThat(budget).isAtMost(MAX_MEMORY_BACKEND_BYTES)
    }

    @Test
    fun `budget never exceeds the hard ceiling on a very large heap`() {
        assertThat(memoryBackendByteBudget(Long.MAX_VALUE)).isEqualTo(MAX_MEMORY_BACKEND_BYTES)
    }

    @Test
    fun `budget has a floor so a tiny heap still keeps a rewind window`() {
        assertThat(memoryBackendByteBudget(8L * 1024L * 1024L))
            .isEqualTo(MIN_MEMORY_BACKEND_BYTES)
        assertThat(memoryBackendByteBudget(0L)).isEqualTo(MIN_MEMORY_BACKEND_BYTES)
    }

    @Test
    fun `configured memory depth is clamped to the wall-clock maximum`() {
        val config = TimeshiftConfig(enabled = true, depthMinutes = 30)
        assertThat(config.effectiveDepthMs(LiveTimeshiftBackend.MEMORY))
            .isEqualTo(TimeshiftConfig.MAX_MEMORY_BACKEND_DEPTH_MINUTES * 60_000L)
        assertThat(config.effectiveDepthMs(LiveTimeshiftBackend.DISK))
            .isEqualTo(30L * 60_000L)
    }
}
