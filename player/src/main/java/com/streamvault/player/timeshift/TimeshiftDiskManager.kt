package com.streamvault.player.timeshift

import android.content.Context
import java.io.File

/**
 * Manages the timeshift cache directory:
 *
 * - Enforces a global byte budget across all session data.
 * - Deletes stale session directories left by crashes or force-stops.
 * - Evicts the oldest (LRU) session directories first when over-budget.
 * - Mutation-safe accounting with immediate invalidation / synchronization.
 * - Deduplicates hard links to avoid double-charging snapshot inodes.
 * - Supports concurrent session reservations to prevent oversubscribing the budget.
 *
 * All methods are safe to call from IO threads.
 */
class TimeshiftDiskManager(
    context: Context,
    val maxBudgetBytes: Long = DEFAULT_BUDGET_BYTES
) {
    val timeshiftDir = File(context.cacheDir, "timeshift")

    private val accountingLock = Any()
    @Volatile private var cachedUsageBytes: Long = -1L
    private var reservedBytes: Long = 0L

    /**
     * Deletes every directory under [timeshiftDir] except [activeSessionDir].
     * Safe to call with [activeSessionDir] = null to wipe everything (e.g. on app start
     * before any session exists).
     */
    fun cleanupStaleDirectories(activeSessionDir: File?) {
        synchronized(accountingLock) {
            val entries = timeshiftDir.listFiles() ?: return
            for (entry in entries) {
                if (entry == activeSessionDir) continue
                entry.deleteRecursively()
            }
            invalidateUsageCacheLocked()
        }
    }

    /**
     * Tries to reserve [bytes] from the remaining budget.
     * Returns true if the reservation succeeded, false if it would exceed [maxBudgetBytes].
     */
    fun reserve(bytes: Long): Boolean {
        synchronized(accountingLock) {
            val current = currentUsageBytesLocked(forceRefresh = true)
            if (current + reservedBytes + bytes <= maxBudgetBytes) {
                reservedBytes += bytes
                return true
            }
            return false
        }
    }

    /**
     * Releases a previously made reservation of [bytes].
     */
    fun release(bytes: Long) {
        synchronized(accountingLock) {
            reservedBytes = (reservedBytes - bytes).coerceAtLeast(0L)
        }
    }

    fun currentReservedBytes(): Long = synchronized(accountingLock) { reservedBytes }

    /**
     * Notifies the manager that files were added, deleted, or mutated.
     *
     * Invalidates the cache, so the next usage read re-walks the directory. Used by every path that
     * DELETES files, where the delta is easier to get wrong than to measure.
     */
    fun recordFileMutation() {
        synchronized(accountingLock) {
            invalidateUsageCacheLocked()
        }
    }

    /**
     * Records that [bytes] were written to a NEW file, without invalidating the cache.
     *
     * A8: the progressive capture finalises a chunk every 2 seconds and then immediately checks the
     * budget, so the invalidate-then-re-walk cycle ran a full recursive directory walk - two stat
     * syscalls per file plus a boxed key per file - every 2 seconds for the whole session, over a
     * directory that reaches ~900 chunk files at the default 30-minute depth.
     *
     * The increment is exact because each chunk is a new file. It is deliberately skipped when the
     * cache is already invalid, so a stale cache cannot be resurrected with a partial total - the
     * pending walk still sees the bytes, because they are on disk by the time this is called. Deletes
     * continue to invalidate, which keeps the cached total from ever drifting BELOW reality, the
     * direction that would let timeshift overrun its budget.
     */
    fun recordBytesWritten(bytes: Long) {
        if (bytes <= 0L) return
        synchronized(accountingLock) {
            if (cachedUsageBytes >= 0L) {
                cachedUsageBytes += bytes
            }
        }
    }

    /**
     * Returns the total physical bytes consumed by files under [timeshiftDir],
     * deduplicating hard-links so snapshot files pointing to existing segments are not charged twice.
     */
    fun currentUsageBytes(forceRefresh: Boolean = false): Long {
        synchronized(accountingLock) {
            return currentUsageBytesLocked(forceRefresh)
        }
    }

    private fun currentUsageBytesLocked(forceRefresh: Boolean): Long {
        if (!forceRefresh && cachedUsageBytes >= 0L) {
            return cachedUsageBytes
        }
        if (!timeshiftDir.exists()) {
            cachedUsageBytes = 0L
            return 0L
        }
        val seenKeys = HashSet<Any>()
        var total = 0L
        val files = timeshiftDir.walkTopDown().filter { it.isFile }
        for (file in files) {
            val key = getFileKey(file)
            if (key != null && !seenKeys.add(key)) {
                // Hard link pointing to an already-counted inode: zero additional physical space
                continue
            }
            total += file.length()
        }
        cachedUsageBytes = total
        return total
    }

    private fun getFileKey(file: File): Any? {
        return try {
            val stat = android.system.Os.stat(file.absolutePath)
            Pair(stat.st_dev, stat.st_ino)
        } catch (_: Throwable) {
            getFallbackFileKey(file)
        }
    }

    private fun getFallbackFileKey(file: File): Any? {
        if (android.os.Build.VERSION.SDK_INT in 1 until android.os.Build.VERSION_CODES.O) {
            return file.canonicalPath
        }
        return try {
            readAttributesFileKey(file) ?: file.canonicalPath
        } catch (_: Throwable) {
            file.canonicalPath
        }
    }

    @android.annotation.SuppressLint("NewApi")
    private fun readAttributesFileKey(file: File): Any? {
        return java.nio.file.Files.readAttributes(
            file.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java
        ).fileKey()
    }

    fun invalidateUsageCache() {
        synchronized(accountingLock) {
            invalidateUsageCacheLocked()
        }
    }

    private fun invalidateUsageCacheLocked() {
        cachedUsageBytes = -1L
    }

    /**
     * Returns true when the total usage plus reservations is below [maxBudgetBytes].
     */
    fun isWithinBudget(forceRefresh: Boolean = false): Boolean {
        synchronized(accountingLock) {
            return (currentUsageBytesLocked(forceRefresh) + reservedBytes) < maxBudgetBytes
        }
    }

    /**
     * Deletes stale session directories in oldest-first (LRU) order until usage
     * falls below 80 % of [maxBudgetBytes], or no more stale dirs remain.
     *
     * [activeSessionDir] is never touched.
     */
    fun evictLruUntilWithinBudget(activeSessionDir: File?) {
        synchronized(accountingLock) {
            val staleDirs = timeshiftDir.listFiles()
                ?.filter { it.isDirectory && it != activeSessionDir }
                ?.sortedBy { it.lastModified() }
                ?: return
            val target = (maxBudgetBytes * 0.8).toLong()
            for (dir in staleDirs) {
                if (currentUsageBytesLocked(forceRefresh = true) + reservedBytes < target) break
                dir.deleteRecursively()
            }
            invalidateUsageCacheLocked()
        }
    }

    companion object {
        /** Default 2 GB global budget for all timeshift data. */
        const val DEFAULT_BUDGET_BYTES = 2L * 1024 * 1024 * 1024
    }
}
