package com.streamvault.domain.security

import java.net.URI
import java.util.Locale

private val SAFE_CROSS_ORIGIN_HEADERS = setOf(
    "accept",
    "range",
    "user-agent",
    "accept-encoding",
    "connection",
    "icy-metadata"
)

fun headersSafeForOrigin(
    sourceUrl: String,
    effectiveUrl: String,
    headers: Map<String, String>
): Map<String, String> {
    if (isSameOrigin(sourceUrl, effectiveUrl)) {
        return headers
    }
    return headers.filterKeys { key ->
        key.lowercase(Locale.ROOT) in SAFE_CROSS_ORIGIN_HEADERS
    }
}

fun isSameOrigin(url1: String, url2: String): Boolean {
    val uri1 = runCatching { URI(url1.trim()) }.getOrNull() ?: return false
    val uri2 = runCatching { URI(url2.trim()) }.getOrNull() ?: return false

    val scheme1 = uri1.scheme?.lowercase(Locale.ROOT) ?: return false
    val scheme2 = uri2.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme1 != scheme2) return false

    val host1 = uri1.host?.lowercase(Locale.ROOT) ?: return false
    val host2 = uri2.host?.lowercase(Locale.ROOT) ?: return false
    if (host1 != host2) return false

    val port1 = if (uri1.port != -1) uri1.port else defaultPortForScheme(scheme1)
    val port2 = if (uri2.port != -1) uri2.port else defaultPortForScheme(scheme2)

    return port1 == port2
}

private fun defaultPortForScheme(scheme: String): Int = when (scheme) {
    "http" -> 80
    "https" -> 443
    else -> -1
}
