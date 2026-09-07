package com.streamvault.player.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import com.streamvault.domain.model.VodHttpProtocolMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.player.cache.PlaybackCacheManager
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import androidx.annotation.VisibleForTesting

internal fun shouldUsePlatformHttpDataSource(resolvedStreamType: ResolvedStreamType): Boolean =
    false

// M3: read-stats instrumentation is opt-in; the default build does not pay per-read
// bookkeeping or URL sanitization on the playback path.
internal const val PLAYER_READ_DIAGNOSTICS_DEFAULT = false

internal fun readStatsWrappingEnabled(
    readDiagnosticsEnabled: Boolean,
    resolvedStreamType: ResolvedStreamType
): Boolean = readDiagnosticsEnabled && shouldWrapDataSourceReadStats(resolvedStreamType)

@UnstableApi
class PlayerDataSourceFactoryProvider(
    private val context: Context,
    private val baseClient: OkHttpClient,
    private val readDiagnosticsEnabled: Boolean = PLAYER_READ_DIAGNOSTICS_DEFAULT,
    val cacheManager: PlaybackCacheManager? = null
) {
    private companion object {
        private const val TAG = "PlayerDataSource"
    }

    private data class ClientKey(
        val profile: PlayerTimeoutProfile,
        val forceHttp1: Boolean,
        val port: Int,
        val allowInvalidSsl: Boolean,
        val proxyHost: String,
        val proxyPort: Int?
    )

    private val addressHealthStore = PlayerAddressHealthStore()
    private val clientsByKey = ConcurrentHashMap<ClientKey, OkHttpClient>()

    fun createFactory(
        streamInfo: StreamInfo,
        resolvedStreamType: ResolvedStreamType,
        vodHttpProtocolMode: VodHttpProtocolMode = VodHttpProtocolMode.COMPATIBILITY_HTTP1,
        preload: Boolean = false
    ): Pair<PlayerTimeoutProfile, DataSource.Factory> {
        val profile = PlayerTimeoutProfile.resolve(streamInfo, resolvedStreamType, preload)
        val headers = effectivePlaybackRequestProperties(
            headers = streamInfo.headers,
            userAgent = streamInfo.userAgent
        )
        logRequestShape(streamInfo, headers, preload)
        val forceHttp1 = PlayerHttpProtocolPolicy.forceHttp1(
            resolvedStreamType = resolvedStreamType,
            vodHttpProtocolMode = vodHttpProtocolMode
        )
        val port = streamPort(streamInfo.url)
        val clientKey = ClientKey(
            profile = profile,
            forceHttp1 = forceHttp1,
            port = port,
            allowInvalidSsl = streamInfo.allowInvalidSsl,
            proxyHost = streamInfo.proxyHost.trim(),
            proxyPort = streamInfo.proxyPort
        )
        val client = clientsByKey.computeIfAbsent(clientKey) {
            val builder = if (streamInfo.allowInvalidSsl) {
                baseClient.newBuilder().applyUnsafeTlsBypass()
            } else {
                baseClient.newBuilder()
            }
            builder
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor(CrossHostRedirectInterceptor())
                .addInterceptor(StalkerPlaybackRequestLoggingInterceptor)
                // Some IPTV edges (Cloudflare-protected origins) answer 407 Proxy
                // Authentication Required to requests carrying Accept-Encoding: gzip.
                // Pinning identity prevents OkHttp's transparent gzip from adding the
                // header, matching what AVFoundation/VLC-style clients send.
                .addInterceptor { chain ->
                    val request = chain.request()
                    if (request.header("Accept-Encoding") == null) {
                        chain.proceed(request.newBuilder().header("Accept-Encoding", "identity").build())
                    } else {
                        chain.proceed(request)
                    }
                }
                .connectTimeout(profile.connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(profile.readTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(profile.writeTimeoutMs, TimeUnit.MILLISECONDS)
                .dns(PlayerDnsPolicy.healthAwareDns(port = port, healthStore = addressHealthStore))
                .eventListener(PlayerAddressHealthEventListener(addressHealthStore))
                .apply {
                    if (forceHttp1) {
                        protocols(listOf(Protocol.HTTP_1_1))
                    }
                    streamInfo.httpProxy()?.let { proxy(it) }
                }
                .build()
        }
        if (forceHttp1) {
            Log.i(TAG, "data-source streamType=$resolvedStreamType timeout=$profile httpProtocol=HTTP_1_1")
        }
        val upstreamFactory = OkHttpDataSource.Factory(client).apply {
            if (headers.isNotEmpty()) {
                setDefaultRequestProperties(headers)
            }
        }
        val defaultFactory = DefaultDataSource.Factory(context, upstreamFactory)
        val mediaFactory = if (cacheManager != null) {
            val cache = cacheManager.getCache()
            val cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(defaultFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            DataSource.Factory {
                LivePlaylistBypassDataSource(
                    cacheDataSource = cacheDataSourceFactory.createDataSource(),
                    upstreamDataSource = defaultFactory.createDataSource()
                )
            }
        } else {
            defaultFactory
        }
        // M3: read-stats wrapping is opt-in via an explicit diagnostics session. When
        // disabled, HLS/MPEG-TS sources go straight to the upstream factory with no
        // per-read bookkeeping or URL sanitization on the playback path.
        val factory = if (readStatsWrappingEnabled(readDiagnosticsEnabled, resolvedStreamType)) {
            PlayerDataSourceReadStatsFactory(
                upstream = mediaFactory,
                resolvedStreamType = resolvedStreamType,
                initialTargetUrl = streamInfo.url
            )
        } else {
            mediaFactory
        }
        return profile to factory
    }

    private fun logRequestShape(
        streamInfo: StreamInfo,
        headers: Map<String, String>,
        preload: Boolean
    ) {
        val hasStalkerHeaders = headers.containsKey("X-User-Agent") ||
            headers.containsKey("Authorization") ||
            headers["Cookie"]?.contains("mac=", ignoreCase = true) == true
        if (!hasStalkerHeaders) {
            return
        }
        val uri = runCatching { URI(streamInfo.url) }.getOrNull()
        Log.d(
            TAG,
            "Playback request headers preload=$preload host=${uri?.host.orEmpty()} path=${uri?.path.orEmpty()} " +
                "ua=${!streamInfo.userAgent.isNullOrBlank()} referer=${headers.containsKey("Referer")} " +
                "cookie=${headers.containsKey("Cookie")} auth=${headers.containsKey("Authorization")} " +
                "xua=${headers.containsKey("X-User-Agent")}"
        )
    }

    private fun streamPort(url: String): Int {
        val uri = runCatching { URI(url) }.getOrNull()
        uri?.port?.takeIf { it > 0 }?.let { return it }
        return when (uri?.scheme?.lowercase()) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
    }

    private fun StreamInfo.httpProxy(): Proxy? {
        val host = proxyHost.trim().takeIf { it.isNotBlank() } ?: return null
        val port = proxyPort ?: return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }
}

internal fun effectivePlaybackRequestProperties(
    headers: Map<String, String>,
    userAgent: String?
): Map<String, String> {
    val normalizedUserAgent = userAgent?.trim().orEmpty()
    if (normalizedUserAgent.isBlank()) {
        return headers
    }
    return buildMap(headers.size + 1) {
        headers.forEach { (name, value) ->
            if (!name.equals("User-Agent", ignoreCase = true)) {
                put(name, value)
            }
        }
        put("User-Agent", normalizedUserAgent)
    }
}

private object StalkerPlaybackRequestLoggingInterceptor : Interceptor {
    private const val TAG = "PlayerDataSource"

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        // Debug aid: log every playback request header set (not just stalker-shaped ones),
        // so Xtream/M3U TS and HLS requests are visible in logcat for 407/403 diagnosis.
        val shouldLog = request.hasStalkerPlaybackShape() || request.url.toString().contains("/live/")
        if (shouldLog) {
            Log.d(
                TAG,
                "Playback request actual method=${request.method} target=${PlaybackLogSanitizer.sanitizeUrl(request.url.toString())} " +
                    "ua=${request.header("User-Agent") != null} referer=${request.header("Referer") != null} " +
                    "cookie=${request.header("Cookie") != null} auth=${request.header("Authorization") != null} " +
                    "xua=${request.header("X-User-Agent") != null} range=${request.header("Range") != null} " +
                    "acceptEncoding=${request.header("Accept-Encoding")?.take(24).orEmpty()} cookieKeys=${request.cookieKeySummary()}"
            )
        }
        val response = chain.proceed(request)
        if (shouldLog) {
            Log.d(
                TAG,
                "Playback response actual target=${PlaybackLogSanitizer.sanitizeUrl(request.url.toString())} " +
                    "code=${response.code} length=${response.header("Content-Length").orEmpty()} " +
                    "type=${response.header("Content-Type").orEmpty()}"
            )
        }
        return response
    }

    private fun okhttp3.Request.hasStalkerPlaybackShape(): Boolean {
        val path = url.encodedPath.lowercase()
        return header("X-User-Agent") != null ||
            header("Authorization") != null ||
            header("Cookie")?.contains("mac=", ignoreCase = true) == true ||
            path.endsWith("/play/live.php") ||
            path.endsWith("/play/movie.php")
    }

    private fun okhttp3.Request.cookieKeySummary(): String {
        val cookie = header("Cookie") ?: return ""
        return cookie.split(';')
            .mapNotNull { part -> part.substringBefore('=', missingDelimiterValue = "").trim().takeIf(String::isNotBlank) }
            .take(12)
            .joinToString("|")
    }
}

@UnstableApi
internal class LivePlaylistBypassDataSource(
    private val cacheDataSource: DataSource,
    private val upstreamDataSource: DataSource
) : DataSource {
    private var activeDataSource: DataSource = cacheDataSource

    override fun addTransferListener(transferListener: TransferListener) {
        cacheDataSource.addTransferListener(transferListener)
        upstreamDataSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val path = dataSpec.uri.path.orEmpty().lowercase()
        activeDataSource = if (path.endsWith(".m3u8") || path.endsWith(".mpd")) {
            upstreamDataSource
        } else {
            cacheDataSource
        }
        return activeDataSource.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        activeDataSource.read(buffer, offset, length)

    override fun getUri(): android.net.Uri? = activeDataSource.uri

    override fun getResponseHeaders(): Map<String, List<String>> = activeDataSource.responseHeaders

    override fun close() {
        activeDataSource.close()
    }
}

@VisibleForTesting
internal class CrossHostRedirectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        var redirectCount = 0
        while (response.isRedirect && redirectCount < 20) {
            redirectCount++
            val location = response.header("Location") ?: break
            val nextUrl = request.url.resolve(location) ?: break
            response.close()

            val isSameOrigin = request.url.scheme.equals(nextUrl.scheme, ignoreCase = true) &&
                request.url.host.equals(nextUrl.host, ignoreCase = true) &&
                request.url.port == nextUrl.port

            val requestBuilder = request.newBuilder().url(nextUrl)
            if (!isSameOrigin) {
                requestBuilder.removeHeader("Authorization")
                requestBuilder.removeHeader("Cookie")
                requestBuilder.removeHeader("X-User-Agent")
                requestBuilder.removeHeader("Referer")
                requestBuilder.removeHeader("Host")
                requestBuilder.removeHeader("X-Portal-Token")
            }
            request = requestBuilder.build()
            response = chain.proceed(request)
        }
        return response
    }
}

