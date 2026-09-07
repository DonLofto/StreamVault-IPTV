package com.streamvault.domain.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OriginBoundHttpHeadersTest {
    @Test
    fun `cross origin keeps transport headers and drops credentials and portal headers`() {
        val headers = headersSafeForOrigin(
            sourceUrl = "https://portal.example.com:443/server/load.php",
            effectiveUrl = "https://cdn.example.com/live/stream.ts",
            headers = linkedMapOf(
                "Accept" to "*/*",
                "Range" to "bytes=0-",
                "User-Agent" to "Lavf53.32.100",
                "Authorization" to "Bearer secret",
                "Cookie" to "mac=secret; PHPSESSID=secret",
                "X-User-Agent" to "Model: MAG322",
                "Referer" to "https://portal.example.com/c/",
                "Host" to "portal.example.com",
                "X-Portal-Token" to "secret"
            )
        )

        assertThat(headers).containsExactly(
            "Accept", "*/*",
            "Range", "bytes=0-",
            "User-Agent", "Lavf53.32.100"
        ).inOrder()
    }

    @Test
    fun `same origin including an explicit default port preserves all headers`() {
        val original = linkedMapOf(
            "Authorization" to "Bearer secret",
            "Cookie" to "session=secret",
            "X-User-Agent" to "Model: MAG322",
            "X-Portal-Token" to "secret"
        )

        val headers = headersSafeForOrigin(
            sourceUrl = "https://portal.example.com/c/",
            effectiveUrl = "https://PORTAL.example.com:443/live/stream.ts",
            headers = original
        )

        assertThat(headers).isEqualTo(original)
    }
}
