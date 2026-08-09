package com.streamvault.data.util

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * M8: sampled end-to-end repository timing. Measures cursor-to-domain mapping plus Flow
 * transformation cost with a query label and row count, so slow materialization is not
 * hidden by SQL-setup-only slow-query logging. Disabled (no-op) in normal release builds.
 */
class RepositoryTimingReporter(
    private val enabled: Boolean,
    private val slowThresholdMs: Long = 100L,
    private val sampleEvery: Int = 100
) {
    private val callCounter = AtomicInteger()

    fun <T> measure(
        label: String,
        rowCount: () -> Int = { -1 },
        block: () -> T
    ): T {
        if (!enabled) return block()
        val sampleNow = callCounter.incrementAndGet() % sampleEvery == 0
        val start = SystemClock.elapsedRealtime()
        val result = block()
        val elapsedMs = SystemClock.elapsedRealtime() - start
        if (sampleNow || elapsedMs >= slowThresholdMs) {
            Log.w(
                TAG,
                "repo-timing label=$label rows=${rowCount()} elapsedMs=$elapsedMs"
            )
        }
        return result
    }

    private companion object {
        const val TAG = "StreamVaultRepoTiming"
    }
}
