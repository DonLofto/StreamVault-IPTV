package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class PlayerDataSourceFactoryProviderTest {
    @Test
    fun `effective playback request properties inject explicit user agent`() {
        val headers = effectivePlaybackRequestProperties(
            headers = mapOf("Referer" to "https://portal.example.com/c/"),
            userAgent = "CustomAgent/9.0"
        )

        assertThat(headers).containsExactly(
            "Referer", "https://portal.example.com/c/",
            "User-Agent", "CustomAgent/9.0"
        )
    }

    @Test
    fun `effective playback request properties replace case insensitive user agent header`() {
        val headers = effectivePlaybackRequestProperties(
            headers = mapOf(
                "user-agent" to "StreamVault/1.0.12-beta",
                "Origin" to "https://portal.example.com"
            ),
            userAgent = "CustomAgent/9.0"
        )

        assertThat(headers).containsExactly(
            "Origin", "https://portal.example.com",
            "User-Agent", "CustomAgent/9.0"
        )
    }

    @Test
    fun `effective playback request properties preserve headers when user agent blank`() {
        val original = linkedMapOf(
            "User-Agent" to "ExistingAgent/1.0",
            "Referer" to "https://portal.example.com/c/"
        )

        val headers = effectivePlaybackRequestProperties(
            headers = original,
            userAgent = "  "
        )

        assertThat(headers).isEqualTo(original)
    }

    @Test
    fun `effective playback request properties keep non user agent headers intact`() {
        val headers = effectivePlaybackRequestProperties(
            headers = mapOf(
                "Cookie" to "mac=00%3A11",
                "X-User-Agent" to "Model: MAG322; Link: Ethernet"
            ),
            userAgent = "StalkerAgent/1.0"
        )

        assertThat(headers).containsExactly(
            "Cookie", "mac=00%3A11",
            "X-User-Agent", "Model: MAG322; Link: Ethernet",
            "User-Agent", "StalkerAgent/1.0"
        )
    }

    @Test
    fun `mpeg ts live keeps okhttp data source`() {
        assertThat(shouldUsePlatformHttpDataSource(ResolvedStreamType.MPEG_TS_LIVE)).isFalse()
    }

    @Test
    fun `hls keeps okhttp data source`() {
        assertThat(shouldUsePlatformHttpDataSource(ResolvedStreamType.HLS)).isFalse()
    }

    @Test
    fun `read stats wrap only live stream types`() {
        assertThat(shouldWrapDataSourceReadStats(ResolvedStreamType.HLS)).isTrue()
        assertThat(shouldWrapDataSourceReadStats(ResolvedStreamType.MPEG_TS_LIVE)).isTrue()
        assertThat(shouldWrapDataSourceReadStats(ResolvedStreamType.PROGRESSIVE)).isFalse()
        assertThat(shouldWrapDataSourceReadStats(ResolvedStreamType.DASH)).isFalse()
    }

    @Test
    fun `read stats disabled by default does not wrap live streams`() {
        assertThat(PLAYER_READ_DIAGNOSTICS_DEFAULT).isFalse()
        assertThat(shouldWrapDataSourceReadStats(ResolvedStreamType.HLS)).isTrue()
    }

    @Test
    fun `read stats gate combines diagnostics flag with live stream type`() {
        assertThat(readStatsWrappingEnabled(readDiagnosticsEnabled = false, resolvedStreamType = ResolvedStreamType.HLS)).isFalse()
        assertThat(readStatsWrappingEnabled(readDiagnosticsEnabled = true, resolvedStreamType = ResolvedStreamType.HLS)).isTrue()
        assertThat(readStatsWrappingEnabled(readDiagnosticsEnabled = true, resolvedStreamType = ResolvedStreamType.PROGRESSIVE)).isFalse()
    }

    @Test
    fun crossHostRedirect_drops_portal_credentials() {
        val server1 = okhttp3.mockwebserver.MockWebServer()
        val server2 = okhttp3.mockwebserver.MockWebServer()
        server1.start()
        server2.start()

        try {
            server1.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", server2.url("/stream.ts").toString())
            )
            server2.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("data"))

            val client = okhttp3.OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor(CrossHostRedirectInterceptor())
                .build()

            val request = okhttp3.Request.Builder()
                .url(server1.url("/initial.m3u8"))
                .header("Authorization", "Bearer secret")
                .header("Cookie", "session=123")
                .header("X-User-Agent", "Model: MAG250")
                .header("Referer", "https://portal.example.com/c/")
                .header("X-Portal-Token", "token456")
                .header("Accept", "*/*")
                .build()

            client.newCall(request).execute().use { response ->
                assertThat(response.isSuccessful).isTrue()
            }

            val recorded1 = server1.takeRequest()
            assertThat(recorded1.getHeader("Authorization")).isEqualTo("Bearer secret")

            val recorded2 = server2.takeRequest()
            assertThat(recorded2.getHeader("Authorization")).isNull()
            assertThat(recorded2.getHeader("Cookie")).isNull()
            assertThat(recorded2.getHeader("X-User-Agent")).isNull()
            assertThat(recorded2.getHeader("Referer")).isNull()
            assertThat(recorded2.getHeader("X-Portal-Token")).isNull()
            assertThat(recorded2.getHeader("Accept")).isEqualTo("*/*")
        } finally {
            server1.shutdown()
            server2.shutdown()
        }
    }

    @Test
    fun sameHostRedirect_preserves_portal_credentials() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()

        try {
            server.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", server.url("/stream.ts").toString())
            )
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("data"))

            val client = okhttp3.OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor(CrossHostRedirectInterceptor())
                .build()

            val request = okhttp3.Request.Builder()
                .url(server.url("/initial.m3u8"))
                .header("Authorization", "Bearer secret")
                .header("Cookie", "session=123")
                .header("X-User-Agent", "Model: MAG250")
                .header("Referer", "https://portal.example.com/c/")
                .build()

            client.newCall(request).execute().use { response ->
                assertThat(response.isSuccessful).isTrue()
            }

            val recorded1 = server.takeRequest()
            assertThat(recorded1.getHeader("Authorization")).isEqualTo("Bearer secret")

            val recorded2 = server.takeRequest()
            assertThat(recorded2.getHeader("Authorization")).isEqualTo("Bearer secret")
            assertThat(recorded2.getHeader("Cookie")).isEqualTo("session=123")
            assertThat(recorded2.getHeader("X-User-Agent")).isEqualTo("Model: MAG250")
            assertThat(recorded2.getHeader("Referer")).isEqualTo("https://portal.example.com/c/")
        } finally {
            server.shutdown()
        }
    }
}
