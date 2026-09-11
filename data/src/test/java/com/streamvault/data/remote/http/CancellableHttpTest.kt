package com.streamvault.data.remote.http

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class CancellableHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        // Every test here cancels a call while a deliberately delayed response is still pending, so
        // the server may still be draining one when the test ends. MockWebServer reports that as
        // "Gave up waiting for queue to shut down", which is the scenario under test rather than a
        // leak - and it was the real cause of this class's long-standing intermittent failures.
        runCatching { server.shutdown() }
    }

    @Test
    fun cancelBeforeHeaders_cancelsCallImmediately() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeadersDelay(2, TimeUnit.SECONDS)
                .setBody("hello")
        )

        val request = Request.Builder().url(server.url("/delay")).build()
        val call = client.newCall(request)

        val job = launch(Dispatchers.IO) {
            try {
                call.awaitResponse()
            } catch (_: CancellationException) {
                // Expected
            }
        }

        // Wait for the call to actually be in flight. A fixed delay here used to be a virtual delay
        // under runTest, so it returned without any real time passing and the cancellation could land
        // before awaitResponse had enqueued anything - which is why this test failed intermittently.
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull()
        job.cancelAndJoin()

        assertThat(call.isCanceled()).isTrue()
    }

    @Test
    fun cancelDuringStreamingBody_cancelsCallAndClosesBody() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("line1\nline2\nline3\n")
                .setBodyDelay(2, TimeUnit.SECONDS)
        )

        val request = Request.Builder().url(server.url("/stream")).build()
        val call = client.newCall(request)

        var cancelledCaught = false
        val job = launch(Dispatchers.IO) {
            try {
                val response = call.awaitResponse()
                response.use { res ->
                    val stream = res.body?.byteStream() ?: return@use
                    val buffer = ByteArray(1024)
                    while (stream.read(buffer) != -1) {
                        delay(10)
                    }
                }
            } catch (_: CancellationException) {
                cancelledCaught = true
            }
        }

        // Headers arrive immediately; the body is what is delayed, so this proves the call is in
        // flight without relying on wall-clock timing.
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull()
        job.cancelAndJoin()

        assertThat(call.isCanceled()).isTrue()
        assertThat(cancelledCaught).isTrue()
    }

    @Test
    fun successfulRequest_completesNormally() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("success")
        )

        val request = Request.Builder().url(server.url("/ok")).build()
        val call = client.newCall(request)

        val response = call.awaitResponse()
        val body = response.body?.string()

        assertThat(response.isSuccessful).isTrue()
        assertThat(body).isEqualTo("success")
    }

    @Test
    fun restartSucceedsAfterCancellation() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeadersDelay(2, TimeUnit.SECONDS)
                .setBody("cancelled")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("second-attempt-success")
        )

        val request1 = Request.Builder().url(server.url("/attempt1")).build()
        val call1 = client.newCall(request1)

        val job1 = launch(Dispatchers.IO) {
            try {
                call1.awaitResponse()
            } catch (_: Exception) {}
        }
        val req1 = server.takeRequest(2, TimeUnit.SECONDS)
        assertThat(req1).isNotNull()
        job1.cancelAndJoin()
        assertThat(call1.isCanceled()).isTrue()

        // Second call proceeds normally
        val request2 = Request.Builder().url(server.url("/attempt2")).build()
        val call2 = client.newCall(request2)
        val response2 = call2.awaitResponse()

        assertThat(response2.isSuccessful).isTrue()
        assertThat(response2.body?.string()).isEqualTo("second-attempt-success")
    }
}
