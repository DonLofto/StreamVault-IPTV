package com.streamvault.player.cache

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared on-disk cache budgets derived from a single source of truth (available/cache
 * space) and partitioned among HTTP, Coil image, and timeshift caches.
 *
 * M4: stops independent fixed maxima (256 MiB HTTP, 100 MiB image, 2 GiB timeshift)
 * from over-committing a constrained `cacheDir`.
 */
data class AppCacheBudgets(
    val httpCacheBytes: Long,
    val imageCacheBytes: Long,
    val timeshiftBudgetBytes: Long
) {
    val totalBytes: Long get() = httpCacheBytes + imageCacheBytes + timeshiftBudgetBytes
}

internal object AppCacheBudgetPolicy {

    /** Reserved free space we never let persistent caches consume. */
    private const val RESERVED_HEADROOM_BYTES = 512L * 1024 * 1024

    /** Upper bounds, matching the previous independent maxima. */
    private const val DEFAULT_HTTP_BYTES = 256L * 1024 * 1024
    private const val DEFAULT_IMAGE_BYTES = 100L * 1024 * 1024
    private const val MAX_TIMESHIFT_BYTES = 2L * 1024 * 1024 * 1024

    /** Progressive lower bounds used under storage pressure. */
    private const val MIN_HTTP_BYTES = 32L * 1024 * 1024
    private const val MIN_IMAGE_BYTES = 20L * 1024 * 1024
    private const val MIN_TIMESHIFT_BYTES = 256L * 1024 * 1024

    private const val MIN_TOTAL_BUDGET_BYTES = 320L * 1024 * 1024

    fun compute(appCacheSpaceBytes: Long): AppCacheBudgets {
        // Keep explicit headroom so caches never fill the cache dir edge-to-edge.
        val budgetable = (appCacheSpaceBytes - RESERVED_HEADROOM_BYTES).coerceAtLeast(0L)
        val total = budgetable.coerceAtLeast(MIN_TOTAL_BUDGET_BYTES)

        val http = (total * 15 / 100).coerceIn(MIN_HTTP_BYTES, DEFAULT_HTTP_BYTES)
        val image = (total * 6 / 100).coerceIn(MIN_IMAGE_BYTES, DEFAULT_IMAGE_BYTES)
        val remaining = total - http - image
        val timeshift = remaining.coerceIn(MIN_TIMESHIFT_BYTES, MAX_TIMESHIFT_BYTES)
        return AppCacheBudgets(
            httpCacheBytes = http,
            imageCacheBytes = image,
            timeshiftBudgetBytes = timeshift
        )
    }
}

@Singleton
class AppCacheQuota @Inject constructor(
    context: Context
) {
    val budgets: AppCacheBudgets = AppCacheBudgetPolicy.compute(usableCacheSpace(context))

    private fun usableCacheSpace(context: Context): Long {
        // Prefer StorageStatsManager free-space when available, fall back to cacheDir.usableSpace.
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val statsManager = try {
                    context.getSystemService(StorageStatsManager::class.java)
                } catch (_: Throwable) {
                    null
                }
                val free = try {
                    statsManager?.getFreeBytes(StorageManager.UUID_DEFAULT) ?: 0L
                } catch (_: IOException) {
                    0L
                }
                if (free > 0L) free else context.cacheDir?.usableSpace ?: 0L
            } else {
                context.cacheDir?.usableSpace ?: 0L
            }
        } catch (_: Throwable) {
            context.cacheDir?.usableSpace ?: 0L
        }
    }
}
