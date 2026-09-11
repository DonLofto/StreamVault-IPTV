package com.streamvault.data.remote.xtream

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.NetworkTimeoutConfig
import com.streamvault.data.remote.http.HttpRequestProfile
import com.streamvault.data.remote.dto.XtreamLiveStreamRow
import com.streamvault.data.remote.dto.XtreamSeriesInfoResponse
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class OkHttpXtreamApiServiceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun `get classifies 401 as authentication error`() = runTest {
        val service = OkHttpXtreamApiService(
            client = clientReturning(statusCode = 401, body = "{}"),
            json = json
        )

        val failure = runCatching {
            service.getLiveCategories("https://example.test/player_api.php")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XtreamAuthenticationException::class.java)
        assertThat(failure).hasMessageThat().contains("HTTP 401")
    }

    @Test
    fun `get classifies malformed JSON as parsing error`() = runTest {
        val service = OkHttpXtreamApiService(
            client = clientReturning(statusCode = 200, body = "{not-json}"),
            json = json
        )

        val failure = runCatching {
            service.getLiveCategories("https://example.test/player_api.php")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XtreamParsingException::class.java)
        assertThat(failure).hasMessageThat().contains("Malformed JSON")
    }

    @Test
    fun `get classifies transport failures as network errors`() = runTest {
        val service = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { throw SocketTimeoutException("timed out") }
                .build(),
            json = json
        )

        val failure = runCatching {
            service.getLiveCategories("https://example.test/player_api.php")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XtreamNetworkException::class.java)
        assertThat(failure).hasMessageThat().contains("timed out")
    }

    @Test
    fun `streamVodStreams decodes array incrementally`() = runTest {
        val service = OkHttpXtreamApiService(
            client = clientReturning(
                statusCode = 200,
                body = """
                    [
                      {"stream_id": "101", "name": "Movie One", "container_extension": "mp4"},
                      {"stream_id": "102", "name": "Movie Two", "container_extension": "mkv"}
                    ]
                """.trimIndent()
            ),
            json = json
        )
        val seenIds = mutableListOf<Long>()

        val count = service.streamVodStreams("https://example.test/player_api.php?action=get_vod_streams") { item ->
            seenIds += item.streamId
        }

        assertThat(count).isEqualTo(2)
        assertThat(seenIds).containsExactly(101L, 102L).inOrder()
    }

    @Test
    fun `streamLiveStreamRows decodes thin live rows incrementally`() = runTest {
        val service = OkHttpXtreamApiService(
            client = clientReturning(
                statusCode = 200,
                body = """
                    [
                      {
                        "stream_id": "101",
                        "name": "Live One",
                        "category_ids": ["10"],
                        "direct_source": "https://cdn.example.test/live/101/master.m3u8?token=abc",
                        "rating": "9.1"
                      },
                      {
                        "stream_id": "102",
                        "name": "Live Two",
                        "container_extension": "ts",
                        "cover_big": "https://img.example.test/cover.jpg"
                      }
                    ]
                """.trimIndent()
            ),
            json = json
        )
        val seen = mutableListOf<Pair<Long, String>>()

        val count = service.streamLiveStreamRows("https://example.test/player_api.php?action=get_live_streams") { row ->
            seen += row.streamId to row.name
        }

        assertThat(count).isEqualTo(2)
        assertThat(seen).containsExactly(101L to "Live One", 102L to "Live Two").inOrder()
    }

    /**
     * A4 guard, and a correction to the plan's premise.
     *
     * The A4 notes assumed the Gson reader's \`isLenient = true\` makes the thin path tolerate
     * non-strict JSON, and warned that a kotlinx streaming decoder would have to match that
     * leniency. Measured on the current code, it does NOT: an unquoted string value is rejected with
     * \`XtreamParsingException\`, because \`JsonParser.parseReader\` rebuilds the node and
     * \`element.toString()\` re-emits STRICT JSON before kotlinx ever parses it. The leniency flag
     * therefore buys nothing on this path, and the refactor has LESS to preserve than the plan said.
     *
     * This test pins the rejection. If the refactor makes the parser start accepting input that is
     * rejected today, that is a behaviour change - possibly a welcome one, but not a silent one.
     */
    @Test
    fun `streamLiveStreamRows still rejects unquoted values`() = runTest {
        val service = OkHttpXtreamApiService(
            client = clientReturning(
                statusCode = 200,
                body = """[{"stream_id": "101", "name": Live One}]"""
            ),
            json = json
        )
        var emitted = 0

        val failure = runCatching {
            service.streamLiveStreamRows("https://example.test/player_api.php") { emitted++ }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XtreamParsingException::class.java)
        assertThat(failure).hasMessageThat().contains("Malformed JSON")
        assertThat(emitted).isEqualTo(0)
    }

    /**
     * A4 guard: a single bad element aborts the whole stream rather than being skipped, and the
     * failure carries the descriptor hint. The refactor must preserve both - silently skipping bad
     * rows would change what the catalog contains, and losing the hint would regress diagnostics.
     */
    @Test
    fun `streamLiveStreamRows fails on a malformed element mid-array`() = runTest {
        val service = OkHttpXtreamApiService(
            client = clientReturning(
                statusCode = 200,
                body = """[{"stream_id":"101","name":"Live One"}, {not-json}]"""
            ),
            json = json
        )
        var emitted = 0

        val failure = runCatching {
            service.streamLiveStreamRows("https://example.test/player_api.php") { emitted++ }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XtreamParsingException::class.java)
        assertThat(failure).hasMessageThat().contains("Malformed JSON")
        // The good row before the bad one was already handed to the caller - the path streams.
        assertThat(emitted).isEqualTo(1)
    }

    @Test
    fun `streamLiveStreamRows cancels the underlying call when coroutine times out`() = runTest {
        val requestStarted = CountDownLatch(1)
        val cancellationObserved = CountDownLatch(1)
        val service = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    requestStarted.countDown()
                    while (!chain.call().isCanceled()) {
                        Thread.sleep(10)
                    }
                    cancellationObserved.countDown()
                    throw IOException("Canceled")
                })
                .build(),
            json = json
        )

        val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val requestJob = requestScope.launch {
            service.streamLiveStreamRows(
                "https://example.test/player_api.php?action=get_live_streams&category_id=7"
            ) { }
        }

        assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue()
        requestJob.cancelAndJoin()
        requestScope.cancel()

        assertThat(requestJob.isCancelled).isTrue()
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            assertThat(cancellationObserved.await(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `segmented live requests use segmented timeout profile`() = runTest {
        val seenReadTimeoutMs = AtomicInteger(-1)
        val service = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    seenReadTimeoutMs.set(chain.readTimeoutMillis())
                    Response.Builder()
                        .request(Request.Builder().url(chain.request().url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("test")
                        .body("[]".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = json
        )

        service.streamLiveStreamRows(
            "https://example.test/player_api.php?action=get_live_streams&category_id=7"
        ) { }

        assertThat(seenReadTimeoutMs.get())
            .isEqualTo((NetworkTimeoutConfig.XTREAM_SEGMENTED_READ_TIMEOUT_SECONDS * 1_000L).toInt())
    }

    @Test
    fun `get applies request profile user agent`() = runTest {
        var seenUserAgent: String? = null
        val service = OkHttpXtreamApiService(
            client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    seenUserAgent = chain.request().header("User-Agent")
                    Response.Builder()
                        .request(Request.Builder().url(chain.request().url).build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("test")
                        .body("[]".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = json
        )

        service.getLiveCategories(
            endpoint = "https://example.test/player_api.php",
            requestProfile = HttpRequestProfile(userAgent = "StreamVaultTest/1.0", ownerTag = "provider:7/xtream")
        )

        assertThat(seenUserAgent).isEqualTo("StreamVaultTest/1.0")
    }

    @Test
    fun `series info parser tolerates flattened info and nested episode objects`() {
        val payload = """
            {
              "name": "Example Series",
              "cover_big": "https://img.example.test/cover.jpg",
              "releasedate": "2024-04-01",
              "episodes": {
                "1": {
                  "101": {
                    "id": "101",
                    "episode_num": "1",
                    "title": "Pilot",
                    "season": "1",
                    "container_extension": "mp4",
                    "info": {
                      "plot": "First episode"
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val response = json.decodeFromString<XtreamSeriesInfoResponse>(payload)

        assertThat(response.info).isNotNull()
        assertThat(response.info?.name).isEqualTo("Example Series")
        assertThat(response.info?.releaseDateAlt).isEqualTo("2024-04-01")
        assertThat(response.episodes.keys).containsExactly("1")
        assertThat(response.episodes["1"]).hasSize(1)
        assertThat(response.episodes["1"]?.first()?.title).isEqualTo("Pilot")
    }

    @Test
    fun `loadLiveCategory returns thin result with a single request`() = runTest {
        val requestCount = AtomicInteger(0)
        val service = OkHttpXtreamApiService(
            client = clientReturningCounting(
                requestCount = requestCount,
                statusCode = 200,
                body = """
                    [{"num":1,"name":"BBC One","stream_id":1001,"stream_icon":null}]
                """.trimIndent()
            ),
            json = json
        )
        val rows = mutableListOf<XtreamLiveStreamRow>()
        val result = service.loadLiveCategory(
            endpoint = "https://example.test/player_api.php?action=get_live_streams&category_id=1",
            maxBufferBytes = 1024 * 1024
        ) { row ->
            rows += row
        }

        assertThat(result).isInstanceOf(LiveCategoryLoad.Thin::class.java)
        assertThat(result.rawCount).isEqualTo(1)
        assertThat(rows).hasSize(1)
        assertThat(rows.single().streamId).isEqualTo(1001L)
        assertThat(requestCount.get()).isEqualTo(1)
    }

    @Test
    fun `loadLiveCategory does not re-request when thin decode fails but body fits the buffer`() = runTest {
        val requestCount = AtomicInteger(0)
        // Valid thin row followed by a `null` element: the thin decoder emits the first
        // row then throws, and the legacy decoder also rejects the same bytes — but the
        // key guarantee is that the whole body is captured and re-decoded locally with
        // NO second HTTP request.
        val service = OkHttpXtreamApiService(
            client = clientReturningCounting(
                requestCount = requestCount,
                statusCode = 200,
                body = """
                    [{"num":1,"name":"BBC One","stream_id":1001},null]
                """.trimIndent()
            ),
            json = json
        )

        val failure = runCatching {
            service.loadLiveCategory(
                endpoint = "https://example.test/player_api.php?action=get_live_streams&category_id=1",
                maxBufferBytes = 1024 * 1024
            ) { }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XtreamParsingException::class.java)
        assertThat(requestCount.get()).isEqualTo(1)
    }

    @Test
    fun `loadLiveCategory re-requests legacy when the body exceeds the buffer cap`() = runTest {
        val requestCount = AtomicInteger(0)
        // Body is larger than the tiny buffer cap: after the thin decode fails, the
        // previous behavior is preserved — a legacy re-request happens.
        val body = """
            [{"num":1,"name":"Large Channel","stream_id":9001,"stream_icon":"https://example.test/${"x".repeat(40)}"},null]
        """.trimIndent()
        val service = OkHttpXtreamApiService(
            client = clientReturningCounting(
                requestCount = requestCount,
                statusCode = 200,
                body = body
            ),
            json = json
        )

        val failure = runCatching {
            service.loadLiveCategory(
                endpoint = "https://example.test/player_api.php?action=get_live_streams&category_id=1",
                maxBufferBytes = 64
            ) { }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XtreamParsingException::class.java)
        assertThat(requestCount.get()).isEqualTo(2)
    }

    private fun clientReturningCounting(
        requestCount: AtomicInteger,
        statusCode: Int,
        body: String
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount.incrementAndGet()
                Response.Builder()
                    .request(Request.Builder().url(chain.request().url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message("test")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    private fun clientReturning(statusCode: Int, body: String): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(Request.Builder().url(chain.request().url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message("test")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }
}
