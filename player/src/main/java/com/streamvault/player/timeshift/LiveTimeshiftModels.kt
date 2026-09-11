package com.streamvault.player.timeshift

import com.streamvault.domain.model.TimeshiftBackendPreference

data class TimeshiftConfig(
    val enabled: Boolean = false,
    val depthMinutes: Int = 30,
    val backendPreference: TimeshiftBackendPreference = TimeshiftBackendPreference.AUTOMATIC
) {
    val depthMs: Long = depthMinutes.coerceIn(15, 60) * 60_000L

    fun effectiveDepthMs(backend: LiveTimeshiftBackend): Long =
        if (backend == LiveTimeshiftBackend.MEMORY)
            depthMinutes.coerceIn(1, MAX_MEMORY_BACKEND_DEPTH_MINUTES) * 60_000L
        else
            depthMs

    companion object {
        const val MAX_MEMORY_BACKEND_DEPTH_MINUTES = 5
    }
}

/**
 * A34 - hard ceiling on bytes the MEMORY backend may retain.
 *
 * The depth cap above is wall-clock only, so retained bytes scale with the stream bitrate:
 * a 5-minute window on a 5-8 Mbit/s channel retains 190-300 MB, against a 192 MB heap class
 * on the Fire TV Stick target. Rewind buffers that large either OOM or push the process into
 * a permanent GC treadmill, so the window now also has a byte ceiling derived from the heap class.
 */
const val MAX_MEMORY_BACKEND_BYTES = 48L * 1024L * 1024L

/** Floor so a small heap still buffers something usable instead of degenerating to no rewind. */
const val MIN_MEMORY_BACKEND_BYTES = 8L * 1024L * 1024L

/**
 * Byte budget for MEMORY-backed timeshift on a heap of [maxHeapBytes].
 *
 * A quarter of the heap class, clamped into [MIN_MEMORY_BACKEND_BYTES]..[MAX_MEMORY_BACKEND_BYTES]:
 * large enough that the buffer never competes with the player's own allocations for the heap.
 */
fun memoryBackendByteBudget(maxHeapBytes: Long): Long =
    (maxHeapBytes / 4L).coerceIn(MIN_MEMORY_BACKEND_BYTES, MAX_MEMORY_BACKEND_BYTES)

enum class LiveTimeshiftBackend {
    NONE,
    DISK,
    MEMORY
}

internal fun resolveLiveTimeshiftBackend(
    preference: TimeshiftBackendPreference,
    snapshotStorageAvailable: Boolean,
    diskStorageAvailable: Boolean
): LiveTimeshiftBackend? {
    if (!snapshotStorageAvailable) return null

    return when (preference) {
        TimeshiftBackendPreference.AUTOMATIC ->
            if (diskStorageAvailable) LiveTimeshiftBackend.DISK else LiveTimeshiftBackend.MEMORY

        TimeshiftBackendPreference.STORAGE ->
            LiveTimeshiftBackend.DISK.takeIf { diskStorageAvailable }

        TimeshiftBackendPreference.MEMORY -> LiveTimeshiftBackend.MEMORY
    }
}

enum class LiveTimeshiftStatus {
    DISABLED,
    UNSUPPORTED,
    PREPARING,
    LIVE,
    PAUSED_BEHIND_LIVE,
    PLAYING_BEHIND_LIVE,
    BUFFERING,
    FAILED
}

data class LiveTimeshiftState(
    val enabled: Boolean = false,
    val supported: Boolean = false,
    val backend: LiveTimeshiftBackend = LiveTimeshiftBackend.NONE,
    val status: LiveTimeshiftStatus = LiveTimeshiftStatus.DISABLED,
    val bufferStartMs: Long = 0L,
    val bufferEndMs: Long = 0L,
    val liveEdgePositionMs: Long = 0L,
    val currentOffsetFromLiveMs: Long = 0L,
    val bufferedDurationMs: Long = 0L,
    val message: String? = null
) {
    val canSeekToLive: Boolean = supported && currentOffsetFromLiveMs > 1_000L
    val isActive: Boolean = enabled && supported && status != LiveTimeshiftStatus.DISABLED && status != LiveTimeshiftStatus.UNSUPPORTED
}

internal data class LiveTimeshiftSnapshot(
    val url: String,
    val durationMs: Long,
    val backend: LiveTimeshiftBackend
)
