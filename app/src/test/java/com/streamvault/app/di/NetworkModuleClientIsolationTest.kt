package com.streamvault.app.di

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * A20 - the H6 traffic-class separation was real only for M3U/Xtream sync. Stalker ran on the main
 * client, and the EPG client was derived with newBuilder(), which copies the Dispatcher and
 * ConnectionPool **by reference**, so neither owned its connection slots.
 *
 * OkHttpClientIsolationTest covers the helper. This covers the wiring: that the client the DI graph
 * actually hands out for Stalker is isolated from the main one, so a future edit cannot quietly go
 * back to newBuilder() and still pass.
 */
class NetworkModuleClientIsolationTest {

    private fun mainClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 10
        })
        .build()

    @Test
    fun `the Stalker client does not share the main client's dispatcher or connection pool`() {
        val main = mainClient()

        val stalker = NetworkModule.provideStalkerClient(main)

        assertThat(stalker.dispatcher).isNotSameInstanceAs(main.dispatcher)
        assertThat(stalker.connectionPool).isNotSameInstanceAs(main.connectionPool)
    }

    @Test
    fun `the Stalker client is bounded well below the main client's capacity`() {
        val main = mainClient()

        val stalker = NetworkModule.provideStalkerClient(main)

        // A slow portal must not occupy the slots playback and multi-view rely on.
        assertThat(stalker.dispatcher.maxRequests).isEqualTo(4)
        assertThat(stalker.dispatcher.maxRequestsPerHost).isEqualTo(2)
        assertThat(stalker.dispatcher.maxRequests).isLessThan(main.dispatcher.maxRequests)
    }

    @Test
    fun `the Stalker client keeps the rest of the main client's configuration`() {
        val main = mainClient()

        val stalker = NetworkModule.provideStalkerClient(main)

        assertThat(stalker.connectTimeoutMillis).isEqualTo(main.connectTimeoutMillis)
        assertThat(stalker.followRedirects).isEqualTo(main.followRedirects)
        assertThat(stalker.interceptors.size).isEqualTo(main.interceptors.size)
    }

    @Test
    fun `a plain newBuilder derivation would have shared both, which is the defect`() {
        val main = mainClient()

        val derived = main.newBuilder().build()

        assertThat(derived.dispatcher).isSameInstanceAs(main.dispatcher)
        assertThat(derived.connectionPool).isSameInstanceAs(main.connectionPool)
    }
}
