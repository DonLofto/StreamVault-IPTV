package com.streamvault.data.remote.http

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * A20 - derive a client that is genuinely isolated from [this] one.
 *
 * [OkHttpClient.newBuilder] copies the **references** to the source client's `Dispatcher` and
 * `ConnectionPool`, so a "derived" client silently shares the source's thread pool and connection
 * slots. That defeats any traffic-class separation built on top of it: a slow Stalker portal or a
 * 200 MB EPG download occupies the same per-host connection slots as playback.
 *
 * This replaces both with fresh instances while inheriting every other setting - cache, timeouts,
 * interceptors and redirect policy - so only the concurrency capacity differs.
 */
fun OkHttpClient.newIsolatedClient(
    maxRequests: Int,
    maxRequestsPerHost: Int,
    maxIdleConnections: Int = 2,
    keepAliveMinutes: Long = 5L
): OkHttpClient = newBuilder()
    .connectionPool(ConnectionPool(maxIdleConnections, keepAliveMinutes, TimeUnit.MINUTES))
    .dispatcher(
        Dispatcher().apply {
            this.maxRequests = maxRequests
            this.maxRequestsPerHost = maxRequestsPerHost
        }
    )
    .build()
