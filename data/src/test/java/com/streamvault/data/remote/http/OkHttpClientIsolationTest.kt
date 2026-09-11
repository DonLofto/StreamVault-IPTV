package com.streamvault.data.remote.http

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * A20 - OkHttpClient.newBuilder() copies the **references** to the source client's Dispatcher and
 * ConnectionPool, so a client derived that way silently shares the source's thread pool and
 * connection slots. Every traffic-class separation built on a plain newBuilder() is therefore not in
 * force. These tests pin that [newIsolatedClient] actually replaces both.
 */
class OkHttpClientIsolationTest {

    private fun sourceClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(11, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(10, 5, TimeUnit.MINUTES))
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 10
        })
        .build()

    @Test
    fun `newBuilder alone shares the dispatcher and connection pool by reference`() {
        val source = sourceClient()
        val derived = source.newBuilder().build()

        // This is the defect A20 is about, pinned so a future refactor cannot quietly reintroduce it.
        assertThat(derived.dispatcher).isSameInstanceAs(source.dispatcher)
        assertThat(derived.connectionPool).isSameInstanceAs(source.connectionPool)
    }

    @Test
    fun `newIsolatedClient replaces both the dispatcher and the connection pool`() {
        val source = sourceClient()

        val isolated = source.newIsolatedClient(maxRequests = 4, maxRequestsPerHost = 2)

        assertThat(isolated.dispatcher).isNotSameInstanceAs(source.dispatcher)
        assertThat(isolated.connectionPool).isNotSameInstanceAs(source.connectionPool)
        assertThat(isolated.dispatcher.maxRequests).isEqualTo(4)
        assertThat(isolated.dispatcher.maxRequestsPerHost).isEqualTo(2)
    }

    @Test
    fun `two isolated clients do not share with each other either`() {
        val source = sourceClient()

        val first = source.newIsolatedClient(maxRequests = 4, maxRequestsPerHost = 2)
        val second = source.newIsolatedClient(maxRequests = 4, maxRequestsPerHost = 2)

        assertThat(first.dispatcher).isNotSameInstanceAs(second.dispatcher)
        assertThat(first.connectionPool).isNotSameInstanceAs(second.connectionPool)
    }

    @Test
    fun `isolation keeps the rest of the source configuration`() {
        val source = sourceClient()

        val isolated = source.newIsolatedClient(maxRequests = 4, maxRequestsPerHost = 2)

        assertThat(isolated.connectTimeoutMillis).isEqualTo(11_000)
        assertThat(isolated.readTimeoutMillis).isEqualTo(22_000)
        assertThat(isolated.followRedirects).isEqualTo(source.followRedirects)
        assertThat(isolated.interceptors.size).isEqualTo(source.interceptors.size)
    }
}
