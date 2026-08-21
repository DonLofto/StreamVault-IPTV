package com.streamvault.data.sync

import android.content.Context
import android.os.StatFs

/**
 * H5: one shared admission policy for EPG staging. Both the background worker and the
 * inline stale-EPG refresh path must pass the same low-memory and free-space gates
 * before downloading and staging a feed, so a large XMLTV never competes with playback
 * or fills flash on a constrained device.
 */
internal class EpgAdmissionPolicy(private val context: Context) {

    fun isAdmitted(): Boolean {
        val lowOnMemory = runCatching { context.isCurrentlyLowOnMemoryForSync() }.getOrDefault(false)
        if (lowOnMemory) return false
        return availableBytes >= MIN_FREE_BYTES
    }

    fun rejectionReason(): String? = when {
        runCatching { context.isCurrentlyLowOnMemoryForSync() }.getOrDefault(false) -> "device low on memory"
        availableBytes < MIN_FREE_BYTES -> "insufficient free space"
        else -> null
    }

    private val availableBytes: Long
        get() = runCatching {
            val stat = StatFs(context.cacheDir.absolutePath)
            stat.availableBytes
        }.getOrDefault(Long.MAX_VALUE)

    private companion object {
        const val MIN_FREE_BYTES = 200L * 1024L * 1024L // 200 MB
    }
}
