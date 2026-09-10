package com.streamvault.data.remote.xtream

import android.util.Log
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.streamvault.data.remote.dto.XtreamAuthResponse
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamEpgResponse
import com.streamvault.data.remote.dto.XtreamLiveStreamRow
import com.streamvault.data.remote.dto.XtreamSeriesInfoResponse
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.dto.XtreamVodInfoResponse
import com.streamvault.data.remote.NetworkTimeoutConfig
import com.streamvault.data.remote.http.HttpRequestProfile
import com.streamvault.data.remote.http.safeRequestIdentitySummary
import com.streamvault.data.remote.http.withRequestProfile
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.net.URI
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Result of a single-request live-category load.
 *
 * The category payload is captured once in memory (up to a caller-defined cap); if the
 * "thin" streaming row decode fails, the legacy decoder re-parses the *same* captured
 * bytes instead of issuing a second HTTP request. [Legacy] carries the full
 * [XtreamStream] list so callers can map with the legacy response mapper.
 */
sealed class LiveCategoryLoad {
    abstract val rawCount: Int

    /** Thin rows were decoded and delivered through the [loadLiveCategory] callback. */
    data class Thin(override val rawCount: Int) : LiveCategoryLoad()

    /** Thin decode failed and the captured body was re-decoded with the legacy DTO. */
    data class Legacy(override val rawCount: Int, val streams: List<XtreamStream>) : LiveCategoryLoad()
}

@OptIn(ExperimentalSerializationApi::class)
class OkHttpXtreamApiService(
    private val client: OkHttpClient,
    private val json: Json,
    private val defaultRequestProfile: HttpRequestProfile = HttpRequestProfile()
) : XtreamApiService {
    private companion object {
        const val TAG = "OkHttpXtreamApi"
        const val PREVIEW_INPUT_LIMIT = 512
        const val PREVIEW_OUTPUT_LIMIT = 140
        const val RESPONSE_BUDGET_HEADROOM_BYTES = 1L * 1024L * 1024L
        const val MAX_FULL_LIVE_CATALOG_BYTES = 96L * 1024L * 1024L
        const val MAX_FULL_VOD_CATALOG_BYTES = 80L * 1024L * 1024L
        const val MAX_FULL_SERIES_CATALOG_BYTES = 100L * 1024L * 1024L
        const val MAX_PARTIAL_CATALOG_BYTES = 40L * 1024L * 1024L
        const val MAX_EPG_BYTES = 12L * 1024L * 1024L

        /**
         * In-memory cap for a single category payload when the thin decode may need a
         * legacy re-decode of the same body. 16MB covers every observed category on the
         * target provider (largest ~8MB) while keeping two concurrent category loads
         * (LOW tier concurrency = 2) within the 192MB heap of Fire TV Sticks. Payloads
         * larger than this keep the previous streaming path with a legacy re-request.
         */
        const val MAX_BUFFERED_CATEGORY_BODY_BYTES = 16L * 1024L * 1024L
    }

    private enum class RequestProfile {
        STANDARD,
        SEGMENTED_CATALOG,
        HEAVY_CATALOG
    }

    private data class EndpointDescriptor(
        val action: String?,
        val host: String?,
        val path: String?,
        val hint: String,
        val queryKeys: Set<String>
    )

    private val heavyCatalogClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(NetworkTimeoutConfig.XTREAM_HEAVY_READ_TIMEOUT_SECONDS, SECONDS)
            .writeTimeout(NetworkTimeoutConfig.XTREAM_HEAVY_WRITE_TIMEOUT_SECONDS, SECONDS)
            .callTimeout(NetworkTimeoutConfig.XTREAM_HEAVY_CALL_TIMEOUT_SECONDS, SECONDS)
            .build()
    }

    private val segmentedCatalogClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(NetworkTimeoutConfig.XTREAM_SEGMENTED_READ_TIMEOUT_SECONDS, SECONDS)
            .writeTimeout(NetworkTimeoutConfig.XTREAM_SEGMENTED_WRITE_TIMEOUT_SECONDS, SECONDS)
            .callTimeout(NetworkTimeoutConfig.XTREAM_SEGMENTED_CALL_TIMEOUT_SECONDS, SECONDS)
            .build()
    }

    override suspend fun authenticate(
        endpoint: String,
        requestProfile: HttpRequestProfile
    ): XtreamAuthResponse = get(endpoint, requestProfile = requestProfile)

    override suspend fun getLiveCategories(
        endpoint: String,
        requestProfile: HttpRequestProfile
    ): List<XtreamCategory> = get(endpoint, requestProfile = requestProfile)

    override suspend fun getLiveStreams(
        endpoint: String,
        requestProfile: HttpRequestProfile
    ): List<XtreamStream> = get(endpoint, RequestProfile.HEAVY_CATALOG, requestProfile)

    override suspend fun getVodCategories(
        endpoint: String,
        requestProfile: HttpRequestProfile
    ): List<XtreamCategory> = get(endpoint, requestProfile = requestProfile)

    override suspend fun getVodStreams(
        endpoint: String,
        requestProfile: HttpRequestProfile
    ): List<XtreamStream> = get(endpoint, RequestProfile.HEAVY_CATALOG, requestProfile)

    override suspend fun streamVodStreams(
        endpoint: String,
        requestProfile: HttpRequestProfile,
        onItem: suspend (XtreamStream) -> Unit
    ): Int =
        streamArray(
            endpoint = endpoint,
            profile = RequestProfile.HEAVY_CATALOG,
            requestProfile = requestProfile,
            deserializer = XtreamStream.serializer(),
            onItem = onItem
        )

    suspend fun streamLiveStreams(
        endpoint: String,
        requestProfile: HttpRequestProfile = HttpRequestProfile(),
        onItem: suspend (XtreamStream) -> Unit
    ): Int =
        streamArray(
            endpoint = endpoint,
            profile = RequestProfile.HEAVY_CATALOG,
            requestProfile = requestProfile,
            deserializer = XtreamStream.serializer(),
            onItem = onItem
        )

    suspend fun streamLiveStreamRows(
        endpoint: String,
        requestProfile: HttpRequestProfile = HttpRequestProfile(),
        onItem: suspend (XtreamLiveStreamRow) -> Unit
    ): Int =
        streamArray(
            endpoint = endpoint,
            profile = RequestProfile.HEAVY_CATALOG,
            requestProfile = requestProfile,
            deserializer = XtreamLiveStreamRow.serializer(),
            onItem = onItem
        )

    override suspend fun getVodInfo(
        endpoint: String,
        requestProfile: HttpRequestProfile
    ): XtreamVodInfoResponse = get(endpoint, requestProfile = requestProfile)

    override suspend fun getSeriesCategories(
        endpoint: String,
        requestProfile: HttpRequestProfile
    ): List<XtreamCategory> = get(endpoint, requestProfile = requestProfile)

    override suspend fun getSeriesList(
        endpoint: String,
        requestProfile: HttpRequestProfile
    ): List<XtreamSeriesItem> = get(endpoint, RequestProfile.HEAVY_CATALOG, requestProfile)

    override suspend fun streamSeriesList(
        endpoint: String,
        requestProfile: HttpRequestProfile,
        onItem: suspend (XtreamSeriesItem) -> Unit
    ): Int =
        streamArray(
            endpoint = endpoint,
            profile = RequestProfile.HEAVY_CATALOG,
            requestProfile = requestProfile,
            deserializer = XtreamSeriesItem.serializer(),
            onItem = onItem
        )

    override suspend fun getSeriesInfo(
        endpoint: String,
        requestProfile: HttpRequestProfile
    ): XtreamSeriesInfoResponse = get(endpoint, requestProfile = requestProfile)

    override suspend fun getShortEpg(
        endpoint: String,
        requestProfile: HttpRequestProfile
    ): XtreamEpgResponse = get(endpoint, requestProfile = requestProfile)

    override suspend fun getFullEpg(
        endpoint: String,
        requestProfile: HttpRequestProfile
    ): XtreamEpgResponse = get(endpoint, requestProfile = requestProfile)

    private suspend inline fun <reified T> get(
        endpoint: String,
        profile: RequestProfile = RequestProfile.STANDARD,
        requestProfile: HttpRequestProfile = HttpRequestProfile()
    ): T = withContext(Dispatchers.IO) {
        val descriptor = describeEndpoint(endpoint)
        val effectiveProfile = requestProfileFor(descriptor, profile)
        val effectiveRequestProfile = requestProfile.mergedWithDefaults(defaultRequestProfile)
        val request = Request.Builder()
            .url(endpoint)
            .get()
            .build()
            .withRequestProfile(effectiveRequestProfile)
        try {
            val call = clientFor(effectiveProfile).newCall(request)
            executeCancellable(call).use { response ->
                if (!response.isSuccessful) {
                    val message = "HTTP ${response.code}"
                    Log.w(
                        TAG,
                        "Xtream request failed for ${descriptor.hint} (${response.request.safeRequestIdentitySummary(effectiveRequestProfile)}): $message"
                    )
                    when (response.code) {
                        401 -> throw XtreamAuthenticationException(response.code, message)
                        403 -> {
                            if (descriptor.action.isNullOrBlank()) {
                                throw XtreamAuthenticationException(response.code, message)
                            }
                            throw XtreamRequestException(response.code, message)
                        }
                        in 500..599, 429 -> throw XtreamNetworkException(message)
                        else -> throw XtreamRequestException(response.code, message)
                    }
                }
                val body = response.body
                    ?: throw XtreamParsingException("Empty response body from ${descriptor.hint}")
                decodeBodyBounded(
                    body = body,
                    descriptor = descriptor,
                    contentType = response.header("Content-Type"),
                    maxBytes = responseBudgetFor(descriptor)
                )
            }
        } catch (e: XtreamApiException) {
            throw e
        } catch (e: IOException) {
            Log.w(
                TAG,
                "Xtream request network failure for ${descriptor.hint} (${request.safeRequestIdentitySummary(effectiveRequestProfile)}): ${XtreamUrlFactory.sanitizeLogMessage(e.message ?: "Network request failed")}"
            )
            throw XtreamNetworkException(XtreamUrlFactory.sanitizeLogMessage(e.message ?: "Network request failed"), e)
        }
    }

    private fun clientFor(profile: RequestProfile): OkHttpClient = when (profile) {
        RequestProfile.STANDARD -> client
        RequestProfile.SEGMENTED_CATALOG -> segmentedCatalogClient
        RequestProfile.HEAVY_CATALOG -> heavyCatalogClient
    }

    private fun requestProfileFor(
        descriptor: EndpointDescriptor,
        requestedProfile: RequestProfile
    ): RequestProfile {
        return when {
            requestedProfile == RequestProfile.HEAVY_CATALOG && isSegmentedCatalogRequest(descriptor) ->
                RequestProfile.SEGMENTED_CATALOG
            else -> requestedProfile
        }
    }

    private fun describeEndpoint(endpoint: String): EndpointDescriptor {
        val uri = runCatching { URI(endpoint) }.getOrNull()
        val queryParams = uri?.rawQuery
            ?.split('&')
            ?.mapNotNull { token ->
                val key = token.substringBefore('=', "").trim()
                if (key.isBlank()) null else key.lowercase() to token.substringAfter('=', "")
            }
            .orEmpty()
        val action = queryParams
            .firstOrNull { (key, _) -> key == "action" }
            ?.second
            ?.takeIf { it.isNotBlank() }
        val host = uri?.host
        val path = uri?.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val hint = buildString {
            append(host ?: "<unknown-host>")
            if (!path.isNullOrBlank()) append("/").append(path)
            if (!action.isNullOrBlank()) append("?action=").append(action)
        }
        return EndpointDescriptor(
            action = action,
            host = host,
            path = path,
            hint = XtreamUrlFactory.sanitizeLogMessage(hint),
            queryKeys = queryParams.mapTo(linkedSetOf()) { (key, _) -> key }
        )
    }

    private fun responseBudgetFor(descriptor: EndpointDescriptor): Long? {
        val action = descriptor.action?.lowercase().orEmpty()
        val isSegmentedCatalogRequest = isSegmentedCatalogRequest(descriptor)
        return when {
            (action == "get_live_streams" || action == "get_vod_streams" || action == "get_series") &&
                isSegmentedCatalogRequest -> MAX_PARTIAL_CATALOG_BYTES
            action == "get_live_streams" -> MAX_FULL_LIVE_CATALOG_BYTES
            action == "get_vod_streams" -> MAX_FULL_VOD_CATALOG_BYTES
            action == "get_series" -> MAX_FULL_SERIES_CATALOG_BYTES
            action == "get_short_epg" || action == "get_simple_data_table" -> MAX_EPG_BYTES
            else -> null
        }
    }

    private fun isSegmentedCatalogRequest(descriptor: EndpointDescriptor): Boolean {
        val queryKeys = descriptor.queryKeys
        return queryKeys.any { key ->
            key == "category_id" || key == "page" || key == "offset" || key == "items_per_page" || key == "limit"
        }
    }

    private suspend fun <T> streamArray(
        endpoint: String,
        profile: RequestProfile,
        requestProfile: HttpRequestProfile,
        deserializer: DeserializationStrategy<T>,
        onItem: suspend (T) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val descriptor = describeEndpoint(endpoint)
        val effectiveProfile = requestProfileFor(descriptor, profile)
        val effectiveRequestProfile = requestProfile.mergedWithDefaults(defaultRequestProfile)
        val request = Request.Builder()
            .url(endpoint)
            .get()
            .build()
            .withRequestProfile(effectiveRequestProfile)
        try {
            val call = clientFor(effectiveProfile).newCall(request)
            executeCancellable(call).use { response ->
                if (!response.isSuccessful) {
                    val message = "HTTP ${response.code}"
                    Log.w(
                        TAG,
                        "Xtream streamed request failed for ${descriptor.hint} (${response.request.safeRequestIdentitySummary(effectiveRequestProfile)}): $message"
                    )
                    when (response.code) {
                        401 -> throw XtreamAuthenticationException(response.code, message)
                        403 -> {
                            if (descriptor.action.isNullOrBlank()) {
                                throw XtreamAuthenticationException(response.code, message)
                            }
                            throw XtreamRequestException(response.code, message)
                        }
                        in 500..599, 429 -> throw XtreamNetworkException(message)
                        else -> throw XtreamRequestException(response.code, message)
                    }
                }
                val body = response.body
                    ?: throw XtreamParsingException("Empty response body from ${descriptor.hint}")
                streamBodyBounded(
                    body = body,
                    descriptor = descriptor,
                    contentType = response.header("Content-Type"),
                    maxBytes = responseBudgetFor(descriptor),
                    deserializer = deserializer,
                    onItem = onItem
                )
            }
        } catch (e: XtreamApiException) {
            throw e
        } catch (e: IOException) {
            Log.w(
                TAG,
                "Xtream streamed request network failure for ${descriptor.hint} (${request.safeRequestIdentitySummary(effectiveRequestProfile)}): ${XtreamUrlFactory.sanitizeLogMessage(e.message ?: "Network request failed")}"
            )
            throw XtreamNetworkException(XtreamUrlFactory.sanitizeLogMessage(e.message ?: "Network request failed"), e)
        }
    }

    private suspend fun executeCancellable(call: Call) = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            call.cancel()
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            }
        })
    }

    /**
     * Single-request live-category loader.
     *
     * Fetches the category payload once. The body is captured into an in-memory buffer
     * (up to [maxBufferBytes]) while it is streamed through the thin row decoder, so
     * that when the thin decode fails the legacy decoder can re-parse the exact same
     * bytes — no second HTTP request is needed for the common malformed/mismatched
     * payload case. Only payloads larger than the buffer cap fall back to the previous
     * behavior (thin decode, then a legacy re-request).
     */
    suspend fun loadLiveCategory(
        endpoint: String,
        requestProfile: HttpRequestProfile = HttpRequestProfile(),
        maxBufferBytes: Long = MAX_BUFFERED_CATEGORY_BODY_BYTES,
        onThinRow: suspend (XtreamLiveStreamRow) -> Unit
    ): LiveCategoryLoad = withContext(Dispatchers.IO) {
        val descriptor = describeEndpoint(endpoint)
        val effectiveProfile = requestProfileFor(descriptor, RequestProfile.HEAVY_CATALOG)
        val effectiveRequestProfile = requestProfile.mergedWithDefaults(defaultRequestProfile)
        val request = Request.Builder()
            .url(endpoint)
            .get()
            .build()
            .withRequestProfile(effectiveRequestProfile)
        try {
            val call = clientFor(effectiveProfile).newCall(request)
            executeCancellable(call).use { response ->
                if (!response.isSuccessful) {
                    val message = "HTTP ${response.code}"
                    Log.w(
                        TAG,
                        "Xtream live category request failed for ${descriptor.hint} (${response.request.safeRequestIdentitySummary(effectiveRequestProfile)}): $message"
                    )
                    when (response.code) {
                        401 -> throw XtreamAuthenticationException(response.code, message)
                        403 -> {
                            if (descriptor.action.isNullOrBlank()) {
                                throw XtreamAuthenticationException(response.code, message)
                            }
                            throw XtreamRequestException(response.code, message)
                        }
                        in 500..599, 429 -> throw XtreamNetworkException(message)
                        else -> throw XtreamRequestException(response.code, message)
                    }
                }
                val body = response.body
                    ?: throw XtreamParsingException("Empty response body from ${descriptor.hint}")
                val effectiveMaxBytes = responseBudgetFor(descriptor)?.plus(RESPONSE_BUDGET_HEADROOM_BYTES)
                val announcedLength = body.contentLength()
                if (effectiveMaxBytes != null && announcedLength > effectiveMaxBytes) {
                    throw XtreamResponseTooLargeException(
                        hint = descriptor.hint,
                        observedBytes = announcedLength,
                        maxAllowedBytes = effectiveMaxBytes
                    )
                }
                val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                val contentType = response.header("Content-Type")

                // Consume the bounded body stream: read the preview, then replay it ahead
                // of the remainder so the capture sees the exact original bytes in order.
                val bounded = BoundedInputStream(
                    delegate = body.byteStream(),
                    descriptor = descriptor,
                    maxAllowedBytes = effectiveMaxBytes
                )
                val previewBytes = readPreviewBytes(bounded)
                if (previewBytes.isEmpty()) {
                    throw XtreamParsingException("Empty response body from ${descriptor.hint}")
                }
                val preview = previewBytes.toString(charset)
                inspectResponseShape(
                    body = preview,
                    contentType = contentType,
                    descriptor = descriptor
                )?.let { throw it }
                val combined = SequenceInputStream(
                    ByteArrayInputStream(previewBytes),
                    bounded
                )
                val capture = CaptureInputStream(combined, maxBytes = maxBufferBytes.toInt())

                val thinResult = runCatching {
                    decodeThinLiveRows(
                        input = capture,
                        charset = charset,
                        preview = preview,
                        descriptor = descriptor,
                        onRow = onThinRow
                    )
                }
                if (thinResult.isSuccess) {
                    return@withContext LiveCategoryLoad.Thin(thinResult.getOrThrow())
                }
                if (!capture.overflowed) {
                    // Thin decode failed but the whole body fits in the buffer: drain the
                    // remainder, then re-decode the same bytes with the legacy DTO.
                    val drainBuffer = ByteArray(8192)
                    while (capture.read(drainBuffer) != -1) {
                        // Drain to complete the capture.
                    }
                    if (capture.overflowed) {
                        // The remaining bytes exceeded the cap while draining; fall back
                        // to a legacy re-request (giant malformed payload).
                        val legacyStreams = getLegacyStreamsForOverflow(
                            endpoint = endpoint,
                            requestProfile = requestProfile,
                            descriptor = descriptor
                        )
                        return@withContext LiveCategoryLoad.Legacy(legacyStreams.size, legacyStreams)
                    }
                    val streams = decodeLegacyLiveStreams(
                        input = ByteArrayInputStream(capture.capturedBytes),
                        preview = preview,
                        descriptor = descriptor
                    )
                    return@withContext LiveCategoryLoad.Legacy(streams.size, streams)
                }
                // Payload exceeded the buffer cap; preserve the previous fallback: re-request
                // with the legacy decoder.
                val legacyStreams = getLegacyStreamsForOverflow(
                    endpoint = endpoint,
                    requestProfile = requestProfile,
                    descriptor = descriptor
                )
                return@withContext LiveCategoryLoad.Legacy(legacyStreams.size, legacyStreams)
            }
        } catch (e: XtreamApiException) {
            throw e
        } catch (e: IOException) {
            Log.w(
                TAG,
                "Xtream live category network failure for ${descriptor.hint} (${request.safeRequestIdentitySummary(effectiveRequestProfile)}): ${XtreamUrlFactory.sanitizeLogMessage(e.message ?: "Network request failed")}"
            )
            throw XtreamNetworkException(XtreamUrlFactory.sanitizeLogMessage(e.message ?: "Network request failed"), e)
        }
    }

    private suspend fun getLegacyStreamsForOverflow(
        endpoint: String,
        requestProfile: HttpRequestProfile,
        descriptor: EndpointDescriptor
    ): List<XtreamStream> {
        Log.w(
            TAG,
            "Xtream live category payload for ${descriptor.hint} exceeded the in-memory buffer; falling back to a legacy re-request."
        )
        return get(endpoint, RequestProfile.HEAVY_CATALOG, requestProfile)
    }

    private suspend fun decodeThinLiveRows(
        input: InputStream,
        charset: Charset,
        preview: String,
        descriptor: EndpointDescriptor,
        onRow: suspend (XtreamLiveStreamRow) -> Unit
    ): Int {
        val reader = JsonReader(InputStreamReader(input, charset))
        reader.isLenient = true
        return when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                var emittedCount = 0
                reader.beginArray()
                while (reader.hasNext()) {
                    val element = try {
                        JsonParser.parseReader(reader)
                    } catch (e: RuntimeException) {
                        throw XtreamParsingException(
                            "Malformed JSON from ${descriptor.hint}${sanitizedPreview(preview)?.let { " (preview=$it)" } ?: ""}",
                            e
                        )
                    }
                    val row = try {
                        json.decodeFromString(XtreamLiveStreamRow.serializer(), element.toString())
                    } catch (e: SerializationException) {
                        throw XtreamParsingException(
                            "Malformed JSON from ${descriptor.hint}${sanitizedPreview(preview)?.let { " (preview=$it)" } ?: ""}",
                            e
                        )
                    }
                    onRow(row)
                    emittedCount++
                }
                reader.endArray()
                emittedCount
            }
            JsonToken.NULL -> {
                reader.nextNull()
                0
            }
            else -> throw XtreamParsingException(
                "Expected JSON array from ${descriptor.hint}${sanitizedPreview(preview)?.let { " (preview=$it)" } ?: ""}"
            )
        }
    }

    private fun decodeLegacyLiveStreams(
        input: InputStream,
        preview: String,
        descriptor: EndpointDescriptor
    ): List<XtreamStream> {
        return try {
            json.decodeFromStream<List<XtreamStream>>(input)
        } catch (e: SerializationException) {
            throw XtreamParsingException(
                "Malformed JSON from ${descriptor.hint}${sanitizedPreview(preview)?.let { " (preview=$it)" } ?: ""}",
                e
            )
        }
    }

    /**
     * Forwards the delegate stream to a bounded in-memory capture while decoding.
     * If the delegate exceeds [maxBytes] the capture is truncated and [overflowed]
     * becomes true (the caller then falls back to a re-request instead of re-decoding
     * a partial body).
     */
    private class CaptureInputStream(
        delegate: InputStream,
        private val maxBytes: Int
    ) : java.io.FilterInputStream(delegate) {
        private val buffer = java.io.ByteArrayOutputStream()
        private var overflowedFlag = false

        val overflowed: Boolean get() = overflowedFlag
        val capturedBytes: ByteArray get() = buffer.toByteArray()

        override fun read(): Int {
            val value = super.read()
            if (value != -1) {
                capture(byteArrayOf(value.toByte()), 0, 1)
            }
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                capture(b, off, n)
            }
            return n
        }

        private fun capture(b: ByteArray, off: Int, n: Int) {
            if (overflowedFlag) return
            val remaining = maxBytes - buffer.size()
            if (remaining >= n) {
                buffer.write(b, off, n)
            } else {
                buffer.write(b, off, remaining.coerceAtLeast(0))
                overflowedFlag = true
            }
        }
    }

    private inline fun <reified T> decodeBodyBounded(
        body: ResponseBody,
        descriptor: EndpointDescriptor,
        contentType: String?,
        maxBytes: Long?
    ): T {
        val effectiveMaxBytes = maxBytes?.plus(RESPONSE_BUDGET_HEADROOM_BYTES)
        val announcedLength = body.contentLength()
        if (effectiveMaxBytes != null && announcedLength > effectiveMaxBytes) {
            throw XtreamResponseTooLargeException(
                hint = descriptor.hint,
                observedBytes = announcedLength,
                maxAllowedBytes = effectiveMaxBytes
            )
        }

        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val input = PushbackInputStream(
            BoundedInputStream(
                delegate = body.byteStream(),
                descriptor = descriptor,
                maxAllowedBytes = effectiveMaxBytes
            ),
            PREVIEW_INPUT_LIMIT
        )
        input.use { stream ->
            val previewBytes = readPreviewBytes(stream)
            if (previewBytes.isEmpty()) {
                throw XtreamParsingException("Empty response body from ${descriptor.hint}")
            }
            val preview = previewBytes.toString(charset)
            inspectResponseShape(
                body = preview,
                contentType = contentType,
                descriptor = descriptor
            )?.let { throw it }
            stream.unread(previewBytes)
            while (true) {
                return try {
                    json.decodeFromStream<T>(stream)
                } catch (e: SerializationException) {
                    throw XtreamParsingException(
                        "Malformed JSON from ${descriptor.hint}${sanitizedPreview(preview)?.let { " (preview=$it)" } ?: ""}",
                        e
                    )
                }
            }
        }
    }

    private suspend fun <T> streamBodyBounded(
        body: ResponseBody,
        descriptor: EndpointDescriptor,
        contentType: String?,
        maxBytes: Long?,
        deserializer: DeserializationStrategy<T>,
        onItem: suspend (T) -> Unit
    ): Int {
        val effectiveMaxBytes = maxBytes?.plus(RESPONSE_BUDGET_HEADROOM_BYTES)
        val announcedLength = body.contentLength()
        if (effectiveMaxBytes != null && announcedLength > effectiveMaxBytes) {
            throw XtreamResponseTooLargeException(
                hint = descriptor.hint,
                observedBytes = announcedLength,
                maxAllowedBytes = effectiveMaxBytes
            )
        }

        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val input = PushbackInputStream(
            BoundedInputStream(
                delegate = body.byteStream(),
                descriptor = descriptor,
                maxAllowedBytes = effectiveMaxBytes
            ),
            PREVIEW_INPUT_LIMIT
        )
        input.use { stream ->
            val previewBytes = readPreviewBytes(stream)
            if (previewBytes.isEmpty()) {
                throw XtreamParsingException("Empty response body from ${descriptor.hint}")
            }
            val preview = previewBytes.toString(charset)
            inspectResponseShape(
                body = preview,
                contentType = contentType,
                descriptor = descriptor
            )?.let { throw it }
            stream.unread(previewBytes)

            val reader = JsonReader(InputStreamReader(stream, charset))
            reader.isLenient = true
            return when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    var emittedCount = 0
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val element = try {
                            JsonParser.parseReader(reader)
                        } catch (e: RuntimeException) {
                            throw XtreamParsingException(
                                "Malformed JSON from ${descriptor.hint}${sanitizedPreview(preview)?.let { " (preview=$it)" } ?: ""}",
                                e
                            )
                        }
                        val item = try {
                            json.decodeFromString(deserializer, element.toString())
                        } catch (e: SerializationException) {
                            throw XtreamParsingException(
                                "Malformed JSON from ${descriptor.hint}${sanitizedPreview(preview)?.let { " (preview=$it)" } ?: ""}",
                                e
                            )
                        }
                        onItem(item)
                        emittedCount++
                    }
                    reader.endArray()
                    emittedCount
                }
                JsonToken.NULL -> {
                    reader.nextNull()
                    0
                }
                else -> throw XtreamParsingException(
                    "Expected JSON array from ${descriptor.hint}${sanitizedPreview(preview)?.let { " (preview=$it)" } ?: ""}"
                )
            }
        }
    }

    private fun readPreviewBytes(input: InputStream): ByteArray {
        val buffer = ByteArray(PREVIEW_INPUT_LIMIT)
        var totalRead = 0
        while (totalRead < PREVIEW_INPUT_LIMIT) {
            val read = input.read(buffer, totalRead, PREVIEW_INPUT_LIMIT - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return if (totalRead == buffer.size) buffer else buffer.copyOf(totalRead)
    }

    private class BoundedInputStream(
        private val delegate: InputStream,
        private val descriptor: EndpointDescriptor,
        private val maxAllowedBytes: Long?
    ) : InputStream() {
        private var totalBytesRead = 0L

        override fun read(): Int {
            val value = delegate.read()
            if (value != -1) {
                recordBytesRead(1)
            }
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = delegate.read(b, off, len)
            if (read > 0) {
                recordBytesRead(read.toLong())
            }
            return read
        }

        override fun close() {
            delegate.close()
        }

        private fun recordBytesRead(bytesRead: Long) {
            totalBytesRead += bytesRead
            val limit = maxAllowedBytes ?: return
            if (totalBytesRead > limit) {
                throw XtreamResponseTooLargeException(
                    hint = descriptor.hint,
                    observedBytes = totalBytesRead,
                    maxAllowedBytes = limit
                )
            }
        }
    }

    private fun inspectResponseShape(
        body: String,
        contentType: String?,
        descriptor: EndpointDescriptor
    ): XtreamParsingException? {
        val trimmed = body.trimStart()
        val normalizedContentType = contentType?.lowercase().orEmpty()
        val preview = sanitizedPreview(body)
        val previewSuffix = if (preview != null) " (preview=$preview)" else ""
        return when {
            trimmed.isBlank() ->
                XtreamParsingException("Blank response body from ${descriptor.hint}")
            normalizedContentType.contains("html") ||
                trimmed.startsWith("<!doctype html", ignoreCase = true) ||
                trimmed.startsWith("<html", ignoreCase = true) ||
                trimmed.contains("<body", ignoreCase = true) ||
                trimmed.contains("</html>", ignoreCase = true) ->
                XtreamParsingException("HTML error page returned from ${descriptor.hint}$previewSuffix")
            trimmed.startsWith("<") ->
                XtreamParsingException("Markup/non-JSON response returned from ${descriptor.hint}$previewSuffix")
            !trimmed.startsWith("{") && !trimmed.startsWith("[") ->
                XtreamParsingException("Non-JSON text response returned from ${descriptor.hint}$previewSuffix")
            else -> null
        }
    }

    private fun sanitizedPreview(body: String): String? {
        val boundedInput = body
            .take(PREVIEW_INPUT_LIMIT)
            .replace(Regex("\\s+"), " ")
            .trim()
        return boundedInput
            .takeIf { it.isNotEmpty() }
            ?.take(PREVIEW_OUTPUT_LIMIT)
            ?.let(XtreamUrlFactory::sanitizeLogMessage)
    }
}
