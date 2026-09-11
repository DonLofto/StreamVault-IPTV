package com.streamvault.data.remote.http

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

/**
 * A38 - the point of routing blocking call sites through [awaitResponse] is that the call is
 * bounded. Read and write timeouts bound individual socket operations, so a server that dribbles
 * bytes just inside the read window would otherwise keep a call alive indefinitely and hold one of
 * the background-sync client's slots. [OkHttpClient.callTimeout] caps the whole call; these tests
 * pin that [awaitResponse] honours it rather than outliving it.
 */
class AwaitResponseCallTimeoutTest {

    @Test
    fun `callTimeout bounds a response that dribbles bytes inside the read window`() = runBlocking {
        val server = MockWebServer()
        // One byte every five seconds: every individual read succeeds well within a 30 s read
        // timeout, so only a total-call cap can stop this.
        server.enqueue(
            MockResponse()
                .setBody("x".repeat(64))
                .throttleBody(1, 5, TimeUnit.SECONDS)
        )
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(1, TimeUnit.SECONDS)
                .build()
            val call = client.newCall(Request.Builder().url(server.url("/dribble")).build())

            val startedAtMs = System.currentTimeMillis()
            val failure = runCatching {
                call.awaitResponse().use { response -> response.body?.string() }
            }.exceptionOrNull()
            val elapsedMs = System.currentTimeMillis() - startedAtMs

            assertThat(failure).isInstanceOf(IOException::class.java)
            assertThat(elapsedMs).isLessThan(10_000L)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `callTimeout does not disturb a response that completes promptly`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("hello"))
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
            val call = client.newCall(Request.Builder().url(server.url("/ok")).build())

            val body = call.awaitResponse().use { response -> response.body?.string() }

            assertThat(body).isEqualTo("hello")
        } finally {
            server.shutdown()
        }
    }
}
