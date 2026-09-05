package com.streamvault.player.timeshift

import android.content.Context
import java.io.File

/**
 * Manages the timeshift cache directory:
 *
 * - Enforces a global byte budget across all session data.
 * - Deletes stale session directories left by crashes or force-stops.
 * - Evicts the oldest (LRU) session directories first when over-budget.
 *
 * All methods are safe to call from IO threads.
 */
class TimeshiftDiskManager(
    context: Context,
    val maxBudgetBytes: Long = DEFAULT_BUDGET_BYTES
) {
    private val timeshiftDir = File(context.cacheDir, "timeshift")

    @Volatile private var cachedUsageBytes: Long = -1L
    @Volatile private var lastUsageCheckMs: Long = 0L
    private val usageCheckLock = Any()

    /**
     * Deletes every directory under [timeshiftDir] except [activeSessionDir].
     * Safe to call with [activeSessionDir] = null to wipe everything (e.g. on app start
     * before any session exists).
     */
    fun cleanupStaleDirectories(activeSessionDir: File?) {
        invalidateUsageCache()
        val entries = timeshiftDir.listFiles() ?: return
        for (entry in entries) {
            if (entry == activeSessionDir) continue
            entry.deleteRecursively()
        }
    }

    /**
     * Returns the total bytes consumed by all files under [timeshiftDir].
     * Throttles disk traversals using a cache TTL unless [forceRefresh] is requested.
     */
    fun currentUsageBytes(forceRefresh: Boolean = false): Long {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedUsageBytes >= 0 && (now - lastUsageCheckMs) < USAGE_CACHE_TTL_MS) {
            return cachedUsageBytes
        }
        synchronized(usageCheckLock) {
            if (!forceRefresh && cachedUsageBytes >= 0 && (System.currentTimeMillis() - lastUsageCheckMs) < USAGE_CACHE_TTL_MS) {
                return cachedUsageBytes
            }
            val calculated = timeshiftDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            cachedUsageBytes = calculated
            lastUsageCheckMs = System.currentTimeMillis()
            return calculated
        }
    }

    fun invalidateUsageCache() {
        cachedUsageBytes = -1L
    }

    /**
     * Returns true when the total usage is below [maxBudgetBytes].
     */
    fun isWithinBudget(forceRefresh: Boolean = false): Boolean = currentUsageBytes(forceRefresh) < maxBudgetBytes

    /**
     * Deletes stale session directories in oldest-first (LRU) order until usage
     * falls below 80 % of [maxBudgetBytes], or no more stale dirs remain.
     *
     * [activeSessionDir] is never touched.
     */
    fun evictLruUntilWithinBudget(activeSessionDir: File?) {
        val staleDirs = timeshiftDir.listFiles()
            ?.filter { it.isDirectory && it != activeSessionDir }
            ?.sortedBy { it.lastModified() }
            ?: return
        val target = (maxBudgetBytes * 0.8).toLong()
        for (dir in staleDirs) {
            if (currentUsageBytes(forceRefresh = true) < target) break
            dir.deleteRecursively()
        }
        invalidateUsageCache()
    }

    companion object {
        /** Default 2 GB global budget for all timeshift data. */
        const val DEFAULT_BUDGET_BYTES = 2L * 1024 * 1024 * 1024
        private const val USAGE_CACHE_TTL_MS = 10_000L // 10 seconds
    }
}
