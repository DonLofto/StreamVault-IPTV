package com.streamvault.player.cache

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppCacheBudgetPolicyTest {

    private val MB = 1024L * 1024L
    private val GB = 1024L * MB

    @Test
    fun compute_capsAtPreviousMaximaWhenSpaceIsPlentiful() {
        val budgets = AppCacheBudgetPolicy.compute(appCacheSpaceBytes = 10L * GB)

        assertThat(budgets.httpCacheBytes).isEqualTo(256L * MB)
        assertThat(budgets.imageCacheBytes).isEqualTo(100L * MB)
        assertThat(budgets.timeshiftBudgetBytes).isAtMost(2L * GB)
    }

    @Test
    fun compute_neverExceedsTheOverallSpacePlusHeadroom() {
        val available = 2L * GB
        val budgets = AppCacheBudgetPolicy.compute(appCacheSpaceBytes = available)

        // Budget must stay clearly below available space so caches never fill it edge-to-edge.
        assertThat(budgets.totalBytes).isLessThan(available)
    }

    @Test
    fun compute_keepsMinimumsUnderStoragePressure() {
        val budgets = AppCacheBudgetPolicy.compute(appCacheSpaceBytes = 400L * MB)

        assertThat(budgets.httpCacheBytes).isAtLeast(32L * MB)
        assertThat(budgets.imageCacheBytes).isAtLeast(20L * MB)
        assertThat(budgets.timeshiftBudgetBytes).isAtLeast(256L * MB)
    }

    @Test
    fun compute_budgetsAreNonNegative() {
        val budgets = AppCacheBudgetPolicy.compute(appCacheSpaceBytes = 0L)
        assertThat(budgets.httpCacheBytes).isAtLeast(0L)
        assertThat(budgets.imageCacheBytes).isAtLeast(0L)
        assertThat(budgets.timeshiftBudgetBytes).isAtLeast(0L)
    }
}
