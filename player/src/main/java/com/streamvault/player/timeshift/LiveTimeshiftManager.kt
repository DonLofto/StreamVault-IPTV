package com.streamvault.player.timeshift

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.system.Os
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.streamvault.domain.model.TimeshiftBackendPreference
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.player.cache.AppCacheQuota
import com.streamvault.player.cache.PlaybackCacheManager
import com.streamvault.player.playback.applyUnsafeTlsBypass
import com.streamvault.player.playback.effectivePlaybackRequestProperties
import com.streamvault.player.playback.LiveTimeshiftPlaybackGate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Compiled once. The HLS master and MPD parsers run on every poll (every few seconds) for the whole
 * life of a live-rewind session, and previously rebuilt these patterns on each pass.
 */
private val BANDWIDTH_REGEX = Regex("""BANDWIDTH=(\d+)""")
private val PT_DAYS_REGEX = Regex("""(\d+(?:\.\d+)?)D""")
private val PT_HOURS_REGEX = Regex("""(\d+(?:\.\d+)?)H""")
private val PT_MINUTES_REGEX = Regex("""(\d+(?:\.\d+)?)M""")
private val PT_SECONDS_REGEX = Regex("""(\d+(?:\.\d+)?)S""")

internal interface LiveTimeshiftManager {
    val state: StateFlow<LiveTimeshiftState>
    suspend fun startSession(streamInfo: StreamInfo, channelKey: String, config: TimeshiftConfig)
    suspend fun stopSession()
    suspend fun createSnapshot(): LiveTimeshiftSnapshot?
    suspend fun releaseRetiredSnapshots()
}

internal data class DashSnapshotPlaylistSegment(
    val fileName: String,
    val durationMs: Long,
    val isInit: Boolean
)

internal fun buildDashSnapshotPlaylist(
    targetDurationSeconds: Int,
    segments: List<DashSnapshotPlaylistSegment>
): String = buildString {
    appendLine("#EXTM3U")
    appendLine("#EXT-X-VERSION:7")
    appendLine("#EXT-X-TARGETDURATION:$targetDurationSeconds")
    appendLine("#EXT-X-MEDIA-SEQUENCE:0")
    segments.firstOrNull { it.isInit }?.let { initSegment ->
        appendLine("#EXT-X-MAP:URI=\"${initSegment.fileName}\"")
    }
    segments.filterNot(DashSnapshotPlaylistSegment::isInit).forEach { segment ->
        appendLine("#EXTINF:${"%.3f".format(Locale.US, segment.durationMs / 1000.0)},")
        appendLine(segment.fileName)
    }
    appendLine("#EXT-X-ENDLIST")
}

/**
 * Infers a timeshift capture type from the parsed URI path/scheme rather than the raw
 * whole URL, so query strings on HLS/DASH URLs (B5) no longer down-grade to progressive.
 */
internal fun inferTimeshiftStreamType(url: String): StreamType {
    val uri = runCatching { java.net.URI(url) }.getOrNull()
    val path = uri?.path?.lowercase(Locale.ROOT) ?: ""
    val scheme = uri?.scheme?.lowercase(Locale.ROOT) ?: url.lowercase(Locale.ROOT)
    return when {
        path.endsWith(".m3u8") -> StreamType.HLS
        path.endsWith(".mpd") -> StreamType.DASH
        path.contains(".isml/manifest") || path.contains(".ism/manifest") || path.endsWith(".ism") || path.endsWith(".isml") ->
            StreamType.SMOOTH_STREAMING
        path.endsWith(".ts") -> StreamType.MPEG_TS
        scheme.startsWith("rtsp") -> StreamType.RTSP
        else -> StreamType.PROGRESSIVE
    }
}

/**
 * B2: a capture failure only reaches FAILED state when the emitting session is still the
 * active one and the throwable is a genuine error (not cooperative cancellation).
 */
internal fun shouldPublishCaptureFailure(isActive: Boolean, throwable: Throwable): Boolean =
    isActive && throwable !is CancellationException

@OptIn(UnstableApi::class)
@Singleton
internal class DefaultLiveTimeshiftManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val playbackGate: LiveTimeshiftPlaybackGate = LiveTimeshiftPlaybackGate(),
    private val appCacheQuota: AppCacheQuota,
    private val playbackCacheManager: PlaybackCacheManager? = null
) : LiveTimeshiftManager, ComponentCallbacks2 {
    private val unsafeOkHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .applyUnsafeTlsBypass()
            .build()
    }
    private val proxiedClients = ConcurrentHashMap<String, OkHttpClient>()

    init {
        context.registerComponentCallbacks(this)
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            scope.launch {
                mutex.withLock {
                    val session = activeSession ?: return@withLock
                    if (session.backend == LiveTimeshiftBackend.MEMORY) {
                        stopSessionLocked()
                        _state.value = _state.value.copy(
                            status = LiveTimeshiftStatus.FAILED,
                            message = "Local rewind stopped: low memory."
                        )
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    override fun onLowMemory() = Unit

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(LiveTimeshiftState())
    override val state: StateFlow<LiveTimeshiftState> = _state.asStateFlow()
    private val diskManager = TimeshiftDiskManager(
        context,
        maxBudgetBytes = appCacheQuota.budgets.timeshiftBudgetBytes
    )

    private var activeSession: Session? = null
    private val retiredSnapshotDirs = ArrayDeque<File>()

    override suspend fun startSession(streamInfo: StreamInfo, channelKey: String, config: TimeshiftConfig) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                stopSessionLocked()
                if (!config.enabled) {
                    _state.value = LiveTimeshiftState(enabled = false, status = LiveTimeshiftStatus.DISABLED)
                    return@withLock
                }
                val support = determineSupport(streamInfo)
                if (!support.supported) {
                    _state.value = LiveTimeshiftState(
                        enabled = true,
                        supported = false,
                        status = LiveTimeshiftStatus.UNSUPPORTED,
                        message = support.reason
                    )
                    return@withLock
                }
                val sessionRoot = context.cacheDir?.takeIf {
                    (it.exists() || it.mkdirs()) && it.canWrite()
                } ?: run {
                    _state.value = LiveTimeshiftState(
                        enabled = true,
                        supported = false,
                        status = LiveTimeshiftStatus.FAILED,
                        message = "App storage is unavailable for local live rewind."
                    )
                    return@withLock
                }
                val sessionDir = File(
                    sessionRoot,
                    "timeshift/${channelKey.hashCode()}-${System.currentTimeMillis()}"
                ).apply { mkdirs() }

                // Crash-safe cleanup: delete all stale timeshift dirs from previous crashes/exits.
                diskManager.cleanupStaleDirectories(activeSessionDir = sessionDir)

                val backend = chooseBackend(config)
                if (backend == null) {
                    sessionDir.deleteRecursively()
                    _state.value = LiveTimeshiftState(
                        enabled = true,
                        supported = false,
                        status = LiveTimeshiftStatus.FAILED,
                        message = backendUnavailableMessage(config.backendPreference)
                    )
                    return@withLock
                }

                // Global budget guard: evict LRU stale dirs if needed, then hard-fail if still over.
                if (backend == LiveTimeshiftBackend.DISK && !diskManager.isWithinBudget()) {
                    diskManager.evictLruUntilWithinBudget(activeSessionDir = sessionDir)
                    if (!diskManager.isWithinBudget()) {
                        sessionDir.deleteRecursively()
                        _state.value = LiveTimeshiftState(
                            enabled = true,
                            supported = false,
                            status = LiveTimeshiftStatus.FAILED,
                            message = "Not enough storage for local live rewind (limit: ${diskManager.maxBudgetBytes / (1024 * 1024 * 1024)} GB)."
                        )
                        return@withLock
                    }
                }
                val session = when (support.streamType) {
                    StreamType.HLS -> HlsSession(streamInfo, config, backend, sessionDir)
                    StreamType.DASH -> DashSession(streamInfo, config, backend, sessionDir)
                    StreamType.SMOOTH_STREAMING,
                    StreamType.MPEG_TS,
                    StreamType.PROGRESSIVE,
                    StreamType.UNKNOWN -> ProgressiveSession(streamInfo, config, backend, sessionDir)
                    StreamType.RTSP -> null
                }
                if (session == null) {
                    _state.value = LiveTimeshiftState(
                        enabled = true,
                        supported = false,
                        status = LiveTimeshiftStatus.UNSUPPORTED,
                        message = "This live stream type cannot use local rewind yet."
                    )
                    return@withLock
                }
                activeSession = session
                _state.value = LiveTimeshiftState(
                    enabled = true,
                    supported = true,
                    backend = backend,
                    status = LiveTimeshiftStatus.PREPARING,
                    message = "Preparing local live rewind…"
                )
                session.job = scope.launch {
                    try {
                        session.capture()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        // B2: only the session that is still active may publish failure.
                        if (shouldPublishCaptureFailure(activeSession === session, error)) {
                            publishFailure(session, error)
                        }
                    }
                }
            }
        }
    }

    private fun publishFailure(session: Session, error: Throwable) {
        _state.value = _state.value.copy(
            enabled = true,
            supported = true,
            backend = session.backend,
            status = LiveTimeshiftStatus.FAILED,
            message = error.message ?: "Local live rewind failed."
        )
    }

    override suspend fun stopSession() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                stopSessionLocked()
                _state.value = LiveTimeshiftState(enabled = false, status = LiveTimeshiftStatus.DISABLED)
            }
        }
    }

    override suspend fun createSnapshot(): LiveTimeshiftSnapshot? {
        return withContext(Dispatchers.IO) {
            val session = mutex.withLock { activeSession } ?: return@withContext null
            try {
                session.snapshotMutex.withLock {
                    val isStillActive = mutex.withLock { activeSession === session }
                    if (!isStillActive) return@withLock null
                    session.activeSnapshotDir?.let { retiredSnapshotDirs.addLast(it) }
                    session.createSnapshot()
                }
            } catch (_: CancellationException) {
                null
            }
        }
    }

    override suspend fun releaseRetiredSnapshots() {
        withContext(Dispatchers.IO) {
            // Grab the list under the mutex, then release it before the potentially slow
            // deleteRecursively() calls so startSession/stopSession are not blocked.
            val dirs = mutex.withLock {
                val copy = retiredSnapshotDirs.toList()
                retiredSnapshotDirs.clear()
                copy
            }
            dirs.forEach { it.deleteRecursively() }
        }
    }

    private suspend fun stopSessionLocked() {
        retiredSnapshotDirs.forEach { it.deleteRecursively() }
        retiredSnapshotDirs.clear()
        activeSession?.let { session ->
            // Mark it non-active first so a late capture error cannot publish FAILED.
            activeSession = null
            session.stop()
            // Await the external capture job before removing its files.
            session.job?.cancelAndJoin()
            // Ensure any in-flight snapshot completes or cancels before sessionDir deletion
            session.snapshotMutex.withLock {
                // Done
            }
            session.sessionDir.deleteRecursively()
            diskManager.recordFileMutation()
        }
    }

    private fun chooseBackend(config: TimeshiftConfig): LiveTimeshiftBackend? =
        resolveLiveTimeshiftBackend(
            preference = config.backendPreference,
            snapshotStorageAvailable = isSnapshotStorageAvailableForTimeshift(),
            diskStorageAvailable = isDiskStorageAvailableForTimeshift()
        )

    private fun backendUnavailableMessage(preference: TimeshiftBackendPreference): String = when (preference) {
        TimeshiftBackendPreference.STORAGE ->
            "The storage backend is unavailable for local live rewind right now."

        TimeshiftBackendPreference.AUTOMATIC,
        TimeshiftBackendPreference.MEMORY ->
            "App storage is unavailable for local live rewind."
    }

    private fun isSnapshotStorageAvailableForTimeshift(): Boolean {
        return try {
            context.cacheDir?.takeIf { (it.exists() || it.mkdirs()) && it.canWrite() } != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun isDiskStorageAvailableForTimeshift(): Boolean {
        if (!isSnapshotStorageAvailableForTimeshift()) return false
        return try {
            context.cacheDir.usableSpace >= MIN_FREE_DISK_BYTES &&
                diskManager.isWithinBudget()
        } catch (_: Throwable) {
            false
        }
    }

    private fun determineSupport(streamInfo: StreamInfo): SupportResult {
        if (streamInfo.drmInfo != null) {
            return SupportResult(false, "DRM-protected streams cannot use local rewind yet.", streamInfo.streamType)
        }
        val type = inferType(streamInfo)
        return when (type) {
            StreamType.RTSP -> SupportResult(false, "RTSP streams cannot use local rewind yet.", type)
            StreamType.SMOOTH_STREAMING -> SupportResult(false, "SmoothStreaming streams cannot use local rewind yet.", type)
            else -> SupportResult(true, null, type)
        }
    }

    private fun inferType(streamInfo: StreamInfo): StreamType {
        if (streamInfo.streamType != StreamType.UNKNOWN) return streamInfo.streamType
        return inferTimeshiftStreamType(streamInfo.url)
    }

    private inner class SupportResult(
        val supported: Boolean,
        val reason: String?,
        val streamType: StreamType
    )

    private abstract inner class Session(
        val streamInfo: StreamInfo,
        val config: TimeshiftConfig,
        val backend: LiveTimeshiftBackend,
        val sessionDir: File
    ) {
        var job: Job? = null
        val snapshotMutex = Mutex()
        var activeSnapshotDir: File? = null
        var fileLinker: (File, File) -> Unit = { source, destination -> linkOrCopySegmentFile(source, destination) }
        val effectiveDepthMs: Long = config.effectiveDepthMs(backend)
        /**
         * A34 - byte ceiling for MEMORY-backed retention, resolved from the heap class once per
         * session. The disk backend retains no payloads, so this is never consulted for it.
         */
        protected val memoryByteBudget: Long =
            memoryBackendByteBudget(Runtime.getRuntime().maxMemory())
        protected val sequence = AtomicLong(0L)
        protected val stateStartMs = System.currentTimeMillis()
        @Volatile private var activeCall: okhttp3.Call? = null

        abstract suspend fun capture()
        abstract suspend fun createSnapshot(): LiveTimeshiftSnapshot?

        open suspend fun stop() {
            activeCall?.cancel()
            job?.cancel()
        }

        protected fun makeRequest(url: String) = Request.Builder().url(url).apply {
            streamInfo.userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
            streamInfo.headers.forEach { (key, value) -> header(key, value) }
        }.build()

        protected fun trackCall(request: Request): okhttp3.Call {
            val call = httpClientFor(streamInfo).newCall(request)
            activeCall = call
            return call
        }

        private fun httpClientFor(streamInfo: StreamInfo): OkHttpClient {
            val proxy = streamInfo.httpProxy()
            if (proxy == null) {
                return if (streamInfo.allowInvalidSsl) unsafeOkHttpClient else okHttpClient
            }
            val key = "${streamInfo.allowInvalidSsl}:${streamInfo.proxyHost.trim()}:${streamInfo.proxyPort}"
            return proxiedClients.computeIfAbsent(key) {
                val builder = if (streamInfo.allowInvalidSsl) {
                    okHttpClient.newBuilder().applyUnsafeTlsBypass()
                } else {
                    okHttpClient.newBuilder()
                }
                builder.proxy(proxy).build()
            }
        }

        private fun StreamInfo.httpProxy(): Proxy? {
            val host = proxyHost.trim().takeIf { it.isNotBlank() } ?: return null
            val port = proxyPort ?: return null
            return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        }

        protected fun clearTrackedCall(call: okhttp3.Call) {
            if (activeCall === call) {
                activeCall = null
            }
        }

        protected suspend fun <T> executeRequest(request: Request, block: (Response) -> T): T {
            val call = trackCall(request)
            return try {
                // A38 - awaitResponse rather than a blocking execute(): cancelling the coroutine
                // cancels the call immediately instead of leaving a thread parked on the socket,
                // and it is the precondition for bounding the call with a timeout.
                call.awaitResponse().use(block)
            } finally {
                clearTrackedCall(call)
            }
        }

        protected suspend fun streamSegmentToDisk(url: String, target: File) {
            val cacheManager = playbackCacheManager
            if (cacheManager != null) {
                try {
                    val cache = cacheManager.getCache()
                    val client = httpClientFor(streamInfo)
                    val upstreamFactory = OkHttpDataSource.Factory(client).apply {
                        val headers = effectivePlaybackRequestProperties(
                            headers = streamInfo.headers,
                            userAgent = streamInfo.userAgent
                        )
                        if (headers.isNotEmpty()) {
                            setDefaultRequestProperties(headers)
                        }
                    }
                    val cacheDataSource = CacheDataSource.Factory()
                        .setCache(cache)
                        .setUpstreamDataSourceFactory(upstreamFactory)
                        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                        .createDataSource()
                    val dataSpec = DataSpec(Uri.parse(url))
                    DataSourceInputStream(cacheDataSource, dataSpec).use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output, bufferSize = PROGRESSIVE_READ_BUFFER_SIZE)
                        }
                    }
                    return
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                }
            }
            executeRequest(makeRequest(url)) { response ->
                if (!response.isSuccessful) throw IOException("Timeshift segment failed with HTTP ${response.code}")
                val body = response.body ?: throw IOException("Timeshift segment returned an empty body")
                body.byteStream().use { input -> target.outputStream().use { output -> input.copyTo(output, bufferSize = PROGRESSIVE_READ_BUFFER_SIZE) } }
            }
        }

        protected suspend fun fetchBytes(url: String): ByteArray {
            val cacheManager = playbackCacheManager
            if (cacheManager != null) {
                try {
                    val cache = cacheManager.getCache()
                    val client = httpClientFor(streamInfo)
                    val upstreamFactory = OkHttpDataSource.Factory(client).apply {
                        val headers = effectivePlaybackRequestProperties(
                            headers = streamInfo.headers,
                            userAgent = streamInfo.userAgent
                        )
                        if (headers.isNotEmpty()) {
                            setDefaultRequestProperties(headers)
                        }
                    }
                    val cacheDataSource = CacheDataSource.Factory()
                        .setCache(cache)
                        .setUpstreamDataSourceFactory(upstreamFactory)
                        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                        .createDataSource()
                    val dataSpec = DataSpec(Uri.parse(url))
                    return DataSourceInputStream(cacheDataSource, dataSpec).use { it.readBytes() }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                }
            }
            return executeRequest(makeRequest(url)) { response ->
                if (!response.isSuccessful) throw IOException("Timeshift segment failed with HTTP ${response.code}")
                response.body?.bytes() ?: ByteArray(0)
            }
        }

        protected fun updateWindow(windowDurationMs: Long, message: String? = null) {
            val now = System.currentTimeMillis()
            _state.value = _state.value.copy(
                enabled = true,
                supported = true,
                backend = backend,
                status = if (windowDurationMs > 0L) LiveTimeshiftStatus.LIVE else LiveTimeshiftStatus.PREPARING,
                bufferStartMs = (now - windowDurationMs).coerceAtLeast(0L),
                bufferEndMs = now,
                liveEdgePositionMs = windowDurationMs,
                bufferedDurationMs = windowDurationMs,
                currentOffsetFromLiveMs = 0L,
                message = message ?: if (windowDurationMs > 0L) "Local live rewind ready." else "Preparing local live rewind…"
            )
        }

        /**
         * Pauses capture while the primary player is buffer-stressed, so the timeshift mirror
         * download never competes with the player's own requests for provider bandwidth.
         */
        protected suspend fun awaitPlaybackNotStressed() {
            while (playbackGate.stressed.value) {
                currentCoroutineContext().ensureActive()
                delay(TIMESHIFT_PLAYBACK_THROTTLE_POLL_MS)
            }
        }

        protected fun resolveRelativeUrl(baseUrl: String, value: String): String {
            return runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault(value)
        }

        protected fun checkDiskAndBudget() {
            val freeSpace = context.cacheDir.usableSpace
            if (freeSpace < MIN_FREE_DISK_BYTES) {
                throw IOException("Insufficient disk space for live rewind (${freeSpace / (1024 * 1024)} MB free, need 200 MB).")
            }
            if (!diskManager.isWithinBudget()) {
                throw IOException("Live rewind storage limit reached (${diskManager.maxBudgetBytes / (1024 * 1024 * 1024)} GB max).")
            }
        }

        protected fun linkOrCopySegmentFile(source: File, destination: File) {
            if (destination.exists()) destination.delete()
            try {
                Os.link(source.absolutePath, destination.absolutePath)
            } catch (_: Throwable) {
                source.copyTo(destination, overwrite = true)
            }
        }
    }

    private inner class ProgressiveSession(
        streamInfo: StreamInfo,
        config: TimeshiftConfig,
        backend: LiveTimeshiftBackend,
        sessionDir: File
    ) : Session(streamInfo, config, backend, sessionDir) {

        private val chunks = ArrayDeque<ProgressiveChunk>()
        private val chunkMutex = Mutex()
        private var runningChunkDurationMs = 0L
        // A34: retained payload bytes, non-zero only for the MEMORY backend.
        private var runningChunkBytes = 0L

        override suspend fun capture() {
            var retryDelay = 1_000L
            var retryCount = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                try {
                    val request = makeRequest(streamInfo.url)
                    val call = trackCall(request)
                    try {
                        // A38 - awaitResponse rather than a blocking execute() (this path is already
                        // a suspend function, so there is no cascade here).
                        call.awaitResponse().use { response ->
                            if (!response.isSuccessful) throw IOException("Timeshift stream failed with HTTP ${response.code}")
                            val input = response.body?.byteStream() ?: throw IOException("Timeshift stream returned an empty body")
                            retryDelay = 1_000L
                            retryCount = 0
                            input.use { source ->
                                var current: ActiveProgressiveChunk? = createChunk()
                                try {
                                    val buffer = ByteArray(PROGRESSIVE_READ_BUFFER_SIZE)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        awaitPlaybackNotStressed()
                                        val read = source.read(buffer)
                                        if (read <= 0) break
                                        val active = current ?: createChunk().also { current = it }
                                        active.write(buffer, read)
                                        if (System.currentTimeMillis() - active.startedAtMs >= PROGRESSIVE_CHUNK_MS) {
                                            finalizeChunk(active)
                                            current = createChunk()
                                        }
                                    }
                                    val active = current
                                    if (active != null) {
                                        if (active.bytesWritten > 0L) {
                                            finalizeChunk(active)
                                        } else {
                                            active.close()
                                        }
                                        current = null
                                    }
                                } finally {
                                    current?.close()
                                }
                            }
                        }
                    } finally {
                        clearTrackedCall(call)
                    }
                    break  // stream ended normally
                } catch (t: Throwable) {
                    currentCoroutineContext().ensureActive()
                    retryCount++
                    if (retryCount > MAX_PROGRESSIVE_RETRIES) throw t
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                }
            }
        }

        override suspend fun createSnapshot(): LiveTimeshiftSnapshot? = snapshotMutex.withLock {
            val snapshotId = sequence.incrementAndGet()
            val tmpDir = File(sessionDir, "snapshot-$snapshotId.tmp").apply { mkdirs() }
            val finalDir = File(sessionDir, "snapshot-$snapshotId")
            try {
                val snapshotFile = File(tmpDir, "buffer.ts")
                val orderedChunks = chunkMutex.withLock { chunks.toList() }
                if (orderedChunks.isEmpty()) {
                    tmpDir.deleteRecursively()
                    return null
                }
                currentCoroutineContext().ensureActive()
                snapshotFile.outputStream().use { output ->
                    orderedChunks.forEach { chunk ->
                        currentCoroutineContext().ensureActive()
                        when {
                            chunk.file != null && chunk.file.exists() -> chunk.file.inputStream().use { it.copyTo(output) }
                            chunk.payload != null -> output.write(chunk.payload)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val durationMs = orderedChunks.sumOf { it.durationMs }
                if (finalDir.exists()) finalDir.deleteRecursively()
                val renamed = tmpDir.renameTo(finalDir)
                val targetDir = if (renamed) finalDir else tmpDir
                activeSnapshotDir = targetDir
                diskManager.recordFileMutation()
                val finalFile = File(targetDir, "buffer.ts")
                LiveTimeshiftSnapshot(
                    url = finalFile.toURI().toString(),
                    durationMs = durationMs,
                    backend = backend
                )
            } catch (t: Throwable) {
                tmpDir.deleteRecursively()
                throw t
            }
        }

        private fun createChunk(): ActiveProgressiveChunk {
            val id = sequence.incrementAndGet()
            val targetFile = if (backend == LiveTimeshiftBackend.DISK) {
                File(sessionDir, "chunk-$id.ts")
            } else {
                null
            }
            return ActiveProgressiveChunk(
                id = id,
                startedAtMs = System.currentTimeMillis(),
                file = targetFile
            )
        }

        private suspend fun finalizeChunk(active: ActiveProgressiveChunk) {
            val output = active.output ?: return  // never written — nothing to finalize
            val payload = if (backend == LiveTimeshiftBackend.MEMORY) (output as ByteArrayOutputStream).toByteArray() else null
            active.close()
            val endedAtMs = System.currentTimeMillis()
            val chunk = ProgressiveChunk(
                id = active.id,
                startedAtMs = active.startedAtMs,
                endedAtMs = endedAtMs,
                durationMs = (endedAtMs - active.startedAtMs).coerceAtLeast(1L),
                file = active.file,
                payload = payload
            )
            val windowDuration = chunkMutex.withLock {
                runningChunkDurationMs += chunk.durationMs
                runningChunkBytes += chunk.payload?.size?.toLong() ?: 0L
                chunks += chunk
                pruneProgressiveChunksLocked()
                while (backend == LiveTimeshiftBackend.DISK && !diskManager.isWithinBudget() && chunks.size > 1) {
                    val removed = chunks.removeFirst()
                    runningChunkDurationMs -= removed.durationMs
                    removed.file?.delete()
                    diskManager.recordFileMutation()
                }
                runningChunkDurationMs
            }
            if (backend == LiveTimeshiftBackend.DISK) {
                // A8: record the exact bytes this chunk added instead of invalidating and forcing a
                // full directory walk. Any eviction above already invalidated, in which case this is
                // a no-op and the walk still sees the truth.
                diskManager.recordBytesWritten(active.bytesWritten)
                checkDiskAndBudget()
            }
            updateWindow(windowDuration)
        }

        private fun ActiveProgressiveChunk.write(buffer: ByteArray, read: Int) {
            val out = output ?: run {
                val created = if (file != null) file.outputStream().buffered() else ByteArrayOutputStream()
                output = created
                created
            }
            out.write(buffer, 0, read)
            bytesWritten += read
        }

        private fun pruneProgressiveChunksLocked() {
            // A34: evict on whichever ceiling is hit first - the wall-clock depth or the
            // MEMORY byte budget. The byte ceiling keeps the last chunk so rewind never
            // degenerates to an empty window on a high-bitrate channel.
            while (chunks.isNotEmpty() &&
                (runningChunkDurationMs > effectiveDepthMs || runningChunkBytes > memoryByteBudget)
            ) {
                if (runningChunkBytes > memoryByteBudget && chunks.size <= 1) break
                val removed = chunks.removeFirst()
                removed.file?.delete()
                runningChunkDurationMs -= removed.durationMs
                runningChunkBytes -= removed.payload?.size?.toLong() ?: 0L
                diskManager.recordFileMutation()
            }
        }
    }

    private inner class HlsSession(
        streamInfo: StreamInfo,
        config: TimeshiftConfig,
        backend: LiveTimeshiftBackend,
        sessionDir: File
    ) : Session(streamInfo, config, backend, sessionDir) {

        private val segments = ArrayDeque<HlsSegmentSnapshot>()
        private val segmentMutex = Mutex()
        private var runningSegmentDurationMs = 0L
        // A34: retained payload bytes, non-zero only for the MEMORY backend.
        private var runningSegmentBytes = 0L
        // Track the highest media sequence number we have processed so far.
        // Segments with sequence <= lastProcessedSequence are skipped (already captured or expired).
        private var lastProcessedSequence = -1L
        // Track discontinuity sequence so ad-break / stream-restart boundaries don't
        // cause us to re-download segments whose sequence numbers reset after a discontinuity.
        private var lastDiscontinuitySequence = -1L

        override suspend fun capture() {
            var currentPlaylistUrl = streamInfo.url
            var consecutiveErrors = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                try {
                    val playlistText = fetchText(currentPlaylistUrl)
                    consecutiveErrors = 0
                    val parsed = parsePlaylist(currentPlaylistUrl, playlistText)
                    when (parsed) {
                        is ParsedHlsPlaylist.Master -> currentPlaylistUrl = parsed.bestVariantUrl
                        is ParsedHlsPlaylist.Media -> {
                            // On a discontinuity-sequence change, reset media-sequence tracking
                            // so segments from the new period are captured fresh.
                            if (parsed.discontinuitySequence != lastDiscontinuitySequence && lastDiscontinuitySequence >= 0L) {
                                lastProcessedSequence = -1L
                            }
                            lastDiscontinuitySequence = parsed.discontinuitySequence

                            parsed.segments.forEach { remoteSegment ->
                                currentCoroutineContext().ensureActive()
                                if (remoteSegment.mediaSequence <= lastProcessedSequence) return@forEach
                                lastProcessedSequence = remoteSegment.mediaSequence
                                awaitPlaybackNotStressed()
                                val retained = retainHlsSegment(remoteSegment)
                                val windowDuration = segmentMutex.withLock {
                                    runningSegmentDurationMs += retained.durationMs
                                    runningSegmentBytes += retained.payload?.size?.toLong() ?: 0L
                                    segments += retained
                                    pruneHlsSegmentsLocked()
                                    while (backend == LiveTimeshiftBackend.DISK && !diskManager.isWithinBudget() && segments.size > 1) {
                                        val removed = segments.removeFirst()
                                        runningSegmentDurationMs -= removed.durationMs
                                        removed.file?.delete()
                                        diskManager.recordFileMutation()
                                    }
                                    runningSegmentDurationMs
                                }
                                if (backend == LiveTimeshiftBackend.DISK) {
                                    diskManager.recordFileMutation()
                                    checkDiskAndBudget()
                                }
                                updateWindow(windowDuration)
                            }
                            if (parsed.endList) break
                            delay((parsed.targetDurationSeconds.coerceAtLeast(2) * 1000L) / 2L)
                        }
                    }
                } catch (t: Throwable) {
                    currentCoroutineContext().ensureActive()
                    consecutiveErrors++
                    if (consecutiveErrors > MAX_HLS_CONSECUTIVE_ERRORS) throw t
                    delay(consecutiveErrors.coerceAtMost(5) * HLS_ERROR_RETRY_DELAY_MS)
                }
            }
        }

        override suspend fun createSnapshot(): LiveTimeshiftSnapshot? = snapshotMutex.withLock {
            val snapshotId = sequence.incrementAndGet()
            val tmpDir = File(sessionDir, "snapshot-$snapshotId.tmp").apply { mkdirs() }
            val finalDir = File(sessionDir, "snapshot-$snapshotId")
            try {
                val snapshotSegments = segmentMutex.withLock { segments.toList() }
                if (snapshotSegments.isEmpty()) {
                    tmpDir.deleteRecursively()
                    return null
                }
                currentCoroutineContext().ensureActive()
                val playlist = File(tmpDir, "index.m3u8")
                val targetDurationSeconds = snapshotSegments.maxOf { ((it.durationMs + 999L) / 1000L).toInt().coerceAtLeast(1) }
                val body = buildString {
                    appendLine("#EXTM3U")
                    appendLine("#EXT-X-VERSION:3")
                    appendLine("#EXT-X-TARGETDURATION:$targetDurationSeconds")
                    appendLine("#EXT-X-MEDIA-SEQUENCE:0")
                    snapshotSegments.forEachIndexed { index, segment ->
                        val fileName = "segment-$index.ts"
                        val outputFile = File(tmpDir, fileName)
                        currentCoroutineContext().ensureActive()
                        when {
                            segment.file != null && segment.file.exists() -> fileLinker(segment.file, outputFile)
                            segment.payload != null -> outputFile.writeBytes(segment.payload)
                        }
                        appendLine("#EXTINF:${"%.3f".format(Locale.US, segment.durationMs / 1000.0)},")
                        appendLine(fileName)
                    }
                    appendLine("#EXT-X-ENDLIST")
                }
                playlist.writeText(body)
                currentCoroutineContext().ensureActive()
                if (finalDir.exists()) finalDir.deleteRecursively()
                val renamed = tmpDir.renameTo(finalDir)
                val targetDir = if (renamed) finalDir else tmpDir
                activeSnapshotDir = targetDir
                diskManager.recordFileMutation()
                val finalPlaylist = File(targetDir, "index.m3u8")
                LiveTimeshiftSnapshot(
                    url = finalPlaylist.toURI().toString(),
                    durationMs = snapshotSegments.sumOf { it.durationMs },
                    backend = backend
                )
            } catch (t: Throwable) {
                tmpDir.deleteRecursively()
                throw t
            }
        }

        private suspend fun retainHlsSegment(remote: RemoteHlsSegment): HlsSegmentSnapshot {
            val id = sequence.incrementAndGet()
            return if (backend == LiveTimeshiftBackend.DISK) {
                val target = File(sessionDir, "segment-$id.ts")
                streamSegmentToDisk(remote.uri, target)
                HlsSegmentSnapshot(remote.uri, remote.durationMs, target, null)
            } else {
                val bytes = fetchBytes(remote.uri)
                HlsSegmentSnapshot(remote.uri, remote.durationMs, null, bytes)
            }
        }

        private fun pruneHlsSegmentsLocked() {
            // A34: same two-ceiling policy as the progressive path — wall-clock depth or the
            // MEMORY byte budget, whichever binds first, always keeping the newest segment.
            while (segments.isNotEmpty() &&
                (runningSegmentDurationMs > effectiveDepthMs || runningSegmentBytes > memoryByteBudget)
            ) {
                if (runningSegmentBytes > memoryByteBudget && segments.size <= 1) break
                val removed = segments.removeFirst()
                removed.file?.delete()
                runningSegmentDurationMs -= removed.durationMs
                runningSegmentBytes -= removed.payload?.size?.toLong() ?: 0L
                diskManager.recordFileMutation()
            }
        }

        private suspend fun fetchText(url: String): String {
            return executeRequest(makeRequest(url)) { response ->
                if (!response.isSuccessful) throw IOException("Timeshift playlist failed with HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }

        private fun parsePlaylist(baseUrl: String, rawText: String): ParsedHlsPlaylist {
            val lines = rawText.lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }) {
                val variants = mutableListOf<Pair<Int, String>>()
                var pendingBandwidth = 0
                lines.forEachIndexed { index, line ->
                    if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                        pendingBandwidth = BANDWIDTH_REGEX
                            .find(line)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: 0
                        val next = lines.getOrNull(index + 1)?.takeIf { !it.startsWith("#") } ?: return@forEachIndexed
                        variants += pendingBandwidth to resolveRelativeUrl(baseUrl, next)
                    }
                }
                val bestVariantUrl = variants.sortedBy { it.first }.getOrNull(variants.size / 2)?.second
                    ?: throw IOException("No playable HLS variants were available.")
                return ParsedHlsPlaylist.Master(bestVariantUrl)
            }

            var targetDurationSeconds = 6
            var endList = false
            var mediaSequence = 0L
            var discontinuitySequence = 0L
            val segments = mutableListOf<RemoteHlsSegment>()
            var currentDurationMs = 6_000L
            var nextSequence = 0L  // assigned after we see EXT-X-MEDIA-SEQUENCE
            var sequenceInitialized = false
            lines.forEach { line ->
                when {
                    line.startsWith("#EXT-X-TARGETDURATION", ignoreCase = true) -> {
                        targetDurationSeconds = line.substringAfter(':', "6").toIntOrNull() ?: 6
                    }
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) -> {
                        mediaSequence = line.substringAfter(':', "0").trim().toLongOrNull() ?: 0L
                        nextSequence = mediaSequence
                        sequenceInitialized = true
                    }
                    line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE", ignoreCase = true) -> {
                        discontinuitySequence = line.substringAfter(':', "0").trim().toLongOrNull() ?: 0L
                    }
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        currentDurationMs = ((line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 6.0) * 1000.0).toLong()
                    }
                    line.startsWith("#EXT-X-ENDLIST", ignoreCase = true) -> endList = true
                    line.startsWith("#") -> Unit
                    else -> {
                        if (!sequenceInitialized) { nextSequence = 0L; sequenceInitialized = true }
                        segments += RemoteHlsSegment(
                            uri = resolveRelativeUrl(baseUrl, line),
                            durationMs = currentDurationMs.coerceAtLeast(1L),
                            mediaSequence = nextSequence
                        )
                        nextSequence++
                    }
                }
            }
            return ParsedHlsPlaylist.Media(
                targetDurationSeconds = targetDurationSeconds,
                endList = endList,
                mediaSequence = mediaSequence,
                discontinuitySequence = discontinuitySequence,
                segments = segments
            )
        }
    }

    private class ProgressiveChunk(
        val id: Long,
        val startedAtMs: Long,
        val endedAtMs: Long,
        val durationMs: Long,
        val file: File?,
        val payload: ByteArray?
    )

    private class ActiveProgressiveChunk(
        val id: Long,
        val startedAtMs: Long,
        val file: File?,
        var output: java.io.OutputStream? = null,
        var bytesWritten: Long = 0L
    ) : AutoCloseable {
        override fun close() {
            try {
                output?.flush()
            } catch (_: Throwable) {}
            try {
                output?.close()
            } catch (_: Throwable) {}
            output = null
        }
    }

    internal class HlsSegmentSnapshot(
        val remoteUrl: String,
        val durationMs: Long,
        val file: File?,
        val payload: ByteArray?
    )

    /**
     * B1: a DASH rolling window that keeps the init segment separate from the media
     * queue, so the zero-duration init can never block media pruning.
     */
    internal class DashWindow(
        private val depthMs: Long,
        private val onEvict: (HlsSegmentSnapshot) -> Unit = {}
    ) {
        private var init: HlsSegmentSnapshot? = null
        private val media = ArrayDeque<HlsSegmentSnapshot>()
        private var mediaDurationMs = 0L

        fun addInit(segment: HlsSegmentSnapshot) {
            init = segment
        }

        fun addMedia(segment: HlsSegmentSnapshot) {
            media.addLast(segment)
            mediaDurationMs += segment.durationMs
            prune()
        }

        /**
         * A35 - insert at the oldest end, for filling the window backwards from the live edge.
         *
         * This keeps the deque chronological (oldest at the front), so [prune] still removes the
         * genuinely oldest segment rather than the one just added.
         */
        fun addMediaFirst(segment: HlsSegmentSnapshot) {
            media.addFirst(segment)
            mediaDurationMs += segment.durationMs
            prune()
        }

        fun initSegment(): HlsSegmentSnapshot? = init

        fun mediaSegments(): List<HlsSegmentSnapshot> = media.toList()

        /** O(1) size accessor: the eviction loop only needs the count, not a copy of the deque. */
        fun mediaSize(): Int = media.size

        fun mediaDurationMs(): Long = mediaDurationMs

        fun allSegments(): List<HlsSegmentSnapshot> = listOfNotNull(init) + media

        fun evictOldestMedia(): HlsSegmentSnapshot? {
            if (media.isEmpty()) return null
            val removed = media.removeFirst()
            mediaDurationMs -= removed.durationMs
            onEvict(removed)
            return removed
        }

        private fun prune() {
            while (mediaDurationMs > depthMs && media.isNotEmpty()) {
                val removed = media.removeFirst()
                mediaDurationMs -= removed.durationMs
                onEvict(removed)
            }
        }
    }

    private data class RemoteHlsSegment(
        val uri: String,
        val durationMs: Long,
        val mediaSequence: Long = 0L
    )

    private sealed interface ParsedHlsPlaylist {
        data class Master(val bestVariantUrl: String) : ParsedHlsPlaylist
        data class Media(
            val targetDurationSeconds: Int,
            val endList: Boolean,
            val mediaSequence: Long,
            val discontinuitySequence: Long,
            val segments: List<RemoteHlsSegment>
        ) : ParsedHlsPlaylist
    }

    /**
     * Captures a DASH live stream by polling the MPD manifest, resolving segments via
     * SegmentTemplate+SegmentTimeline, and writing them to disk/memory. On snapshot,
     * the captured segments are re-packaged as a static HLS playlist so ExoPlayer can
     * play them the same way as HLS snapshots.
     */
    private inner class DashSession(
        streamInfo: StreamInfo,
        config: TimeshiftConfig,
        backend: LiveTimeshiftBackend,
        sessionDir: File
    ) : Session(streamInfo, config, backend, sessionDir) {

        private val segmentMutex = Mutex()
        private val seenSegments = linkedSetOf<String>()
        private val window = DashWindow(
            depthMs = effectiveDepthMs,
            onEvict = { evicted ->
                seenSegments.remove(evicted.remoteUrl)
                evicted.file?.delete()
                diskManager.recordFileMutation()
            }
        )

        override suspend fun capture() {
            var consecutiveErrors = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                try {
                    val mpdText = fetchText(streamInfo.url)
                    consecutiveErrors = 0
                    val parsed = parseMpd(streamInfo.url, mpdText)

                    // Download init segment once (identified by URL; seenSegments deduplicates it).
                    parsed.initSegmentUrl?.let { initUrl ->
                        if (seenSegments.add("__init__:$initUrl")) {
                            val retained = retainSegment(RemoteHlsSegment(initUrl, 0L), isInit = true)
                            segmentMutex.withLock { window.addInit(retained) }
                            if (backend == LiveTimeshiftBackend.DISK) {
                                diskManager.recordFileMutation()
                                checkDiskAndBudget()
                            }
                        }
                    }

                    // A35 - the first poll used to download the whole retention window oldest first, so
                    // the live edge - the part a viewer can actually reach - arrived only after every
                    // older segment, one serial request each. It now fills from the live edge backwards:
                    // newest first, inserted at the oldest end so the window stays chronological, and
                    // stopping once the retention depth is covered. Segments beyond that are marked as
                    // considered rather than downloaded only to be pruned straight back out.
                    val backfilling = window.mediaSize() == 0
                    val pollSegments = if (backfilling) parsed.mediaSegments.asReversed() else parsed.mediaSegments
                    var backfilledMs = 0L
                    pollSegments.forEach { remote ->
                        currentCoroutineContext().ensureActive()
                        if (!seenSegments.add(remote.uri)) return@forEach
                        if (backfilling && backfilledMs >= effectiveDepthMs) return@forEach
                        awaitPlaybackNotStressed()
                        val retained = retainSegment(remote, isInit = false)
                        backfilledMs += retained.durationMs
                        val windowDuration = segmentMutex.withLock {
                            if (backfilling) window.addMediaFirst(retained) else window.addMedia(retained)
                            while (backend == LiveTimeshiftBackend.DISK && !diskManager.isWithinBudget() && window.mediaSize() > 1) {
                                window.evictOldestMedia()
                            }
                            window.mediaDurationMs()
                        }
                        if (backend == LiveTimeshiftBackend.DISK) {
                            diskManager.recordFileMutation()
                            checkDiskAndBudget()
                        }
                        updateWindow(windowDuration)
                    }

                    if (!parsed.isDynamic) break  // VOD / static — no need to re-poll
                    delay(parsed.minimumUpdatePeriodMs.coerceAtLeast(1_000L))
                } catch (t: Throwable) {
                    currentCoroutineContext().ensureActive()
                    consecutiveErrors++
                    if (consecutiveErrors > MAX_DASH_CONSECUTIVE_ERRORS) throw t
                    delay(consecutiveErrors.coerceAtMost(5) * DASH_ERROR_RETRY_DELAY_MS)
                }
            }
        }

        override suspend fun createSnapshot(): LiveTimeshiftSnapshot? = snapshotMutex.withLock {
            val snapshotId = sequence.incrementAndGet()
            val tmpDir = File(sessionDir, "snapshot-$snapshotId.tmp").apply { mkdirs() }
            val finalDir = File(sessionDir, "snapshot-$snapshotId")
            try {
                val all = segmentMutex.withLock { window.allSegments() }
                val snapshotSegments = all.filter { it.durationMs > 0L }  // media only for HLS timing
                if (snapshotSegments.isEmpty()) {
                    tmpDir.deleteRecursively()
                    return null
                }
                currentCoroutineContext().ensureActive()
                val playlist = File(tmpDir, "index.m3u8")
                val targetDurationSeconds = snapshotSegments.maxOf { ((it.durationMs + 999L) / 1000L).toInt().coerceAtLeast(1) }
                var mediaIndex = 0
                val playlistSegments = all.mapIndexed { index, segment ->
                    val isInit = segment.durationMs == 0L
                    val fileName = if (isInit) "init-$index.mp4" else "segment-${mediaIndex++}.mp4"
                    val outputFile = File(tmpDir, fileName)
                    currentCoroutineContext().ensureActive()
                    when {
                        segment.file != null && segment.file.exists() -> fileLinker(segment.file, outputFile)
                        segment.payload != null -> outputFile.writeBytes(segment.payload)
                    }
                    DashSnapshotPlaylistSegment(
                        fileName = fileName,
                        durationMs = segment.durationMs,
                        isInit = isInit
                    )
                }
                val body = buildDashSnapshotPlaylist(
                    targetDurationSeconds = targetDurationSeconds,
                    segments = playlistSegments
                )
                playlist.writeText(body)
                currentCoroutineContext().ensureActive()
                if (finalDir.exists()) finalDir.deleteRecursively()
                val renamed = tmpDir.renameTo(finalDir)
                val targetDir = if (renamed) finalDir else tmpDir
                activeSnapshotDir = targetDir
                diskManager.recordFileMutation()
                val finalPlaylist = File(targetDir, "index.m3u8")
                LiveTimeshiftSnapshot(
                    url = finalPlaylist.toURI().toString(),
                    durationMs = snapshotSegments.sumOf { it.durationMs },
                    backend = backend
                )
            } catch (t: Throwable) {
                tmpDir.deleteRecursively()
                throw t
            }
        }

        private suspend fun retainSegment(remote: RemoteHlsSegment, isInit: Boolean): HlsSegmentSnapshot {
            val id = sequence.incrementAndGet()
            val ext = if (isInit) "init-$id.mp4" else "segment-$id.mp4"
            return if (backend == LiveTimeshiftBackend.DISK) {
                val target = File(sessionDir, ext)
                streamSegmentToDisk(remote.uri, target)
                HlsSegmentSnapshot(remote.uri, remote.durationMs, target, null)
            } else {
                val bytes = fetchBytes(remote.uri)
                HlsSegmentSnapshot(remote.uri, remote.durationMs, null, bytes)
            }
        }

        private suspend fun fetchText(url: String): String {
            return executeRequest(makeRequest(url)) { response ->
                if (!response.isSuccessful) throw IOException("MPD fetch failed: HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }

        /**
         * Parses a DASH MPD and extracts:
         * - Whether it is a live (dynamic) stream
         * - The minimumUpdatePeriod in ms (for live re-poll timing)
         * - The init segment URL for the selected representation
         * - The list of new media segments via SegmentTemplate + SegmentTimeline
         *
         * Selects the middle-bandwidth video Adaptation Set to balance quality vs storage,
         * consistent with the HLS variant selection policy.
         */
        private fun parseMpd(baseUrl: String, rawXml: String): ParsedDashManifest {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val xpp = factory.newPullParser()
            xpp.setInput(rawXml.reader())

            var isDynamic = false
            var minimumUpdatePeriodMs = 5_000L
            var availabilityStartTimeMs = 0L
            var timescale = 1L
            var segmentDuration = 0L
            var startNumber = 1L
            var mediaTemplate = ""
            var initTemplate = ""
            var representationBandwidth = 0
            var representationId = ""

            // Collect all Representations from the first video AdaptationSet.
            // Structure: MPD > Period > AdaptationSet (contentType=video) > Representation
            data class RepresentationInfo(val id: String, val bandwidth: Int, val initTemplate: String, val mediaTemplate: String, val timescale: Long, val segmentDuration: Long, val startNumber: Long)
            val representations = mutableListOf<RepresentationInfo>()
            val timelineSegments = mutableListOf<Pair<Long, Long>>()  // (t, d) pairs

            var inAdaptationSet = false
            var inVideoAdaptationSet = false
            var inRepresentation = false
            var inSegmentTemplate = false
            var inSegmentTimeline = false
            var currentRepBandwidth = 0
            var currentRepId = ""
            var currentInitTemplate = ""
            var currentMediaTemplate = ""
            var currentTimescale = 1L
            var currentSegDuration = 0L
            var currentStartNumber = 1L
            var currentTimelineSegments = mutableListOf<Pair<Long, Long>>()

            var eventType = xpp.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (xpp.name) {
                        "MPD" -> {
                            isDynamic = xpp.getAttributeValue(null, "type")?.equals("dynamic", ignoreCase = true) == true
                            val mup = xpp.getAttributeValue(null, "minimumUpdatePeriod")
                            if (mup != null) minimumUpdatePeriodMs = parsePtDuration(mup)
                            val ast = xpp.getAttributeValue(null, "availabilityStartTime")
                            if (ast != null) availabilityStartTimeMs = parseIso8601ToMs(ast)
                        }
                        "AdaptationSet" -> {
                            inAdaptationSet = true
                            val contentType = xpp.getAttributeValue(null, "contentType")
                                ?: xpp.getAttributeValue(null, "mimeType") ?: ""
                            inVideoAdaptationSet = contentType.contains("video", ignoreCase = true)
                        }
                        "Representation" -> if (inVideoAdaptationSet) {
                            inRepresentation = true
                            currentRepBandwidth = xpp.getAttributeValue(null, "bandwidth")?.toIntOrNull() ?: 0
                            currentRepId = xpp.getAttributeValue(null, "id") ?: ""
                            currentInitTemplate = initTemplate
                            currentMediaTemplate = mediaTemplate
                            currentTimescale = timescale
                            currentSegDuration = segmentDuration
                            currentStartNumber = startNumber
                            currentTimelineSegments = mutableListOf()
                        }
                        "SegmentTemplate" -> {
                            inSegmentTemplate = true
                            val ts = xpp.getAttributeValue(null, "timescale")?.toLongOrNull() ?: 1L
                            val sd = xpp.getAttributeValue(null, "duration")?.toLongOrNull() ?: 0L
                            val sn = xpp.getAttributeValue(null, "startNumber")?.toLongOrNull() ?: 1L
                            val it = xpp.getAttributeValue(null, "initialization") ?: ""
                            val mt = xpp.getAttributeValue(null, "media") ?: ""
                            if (inRepresentation) {
                                currentTimescale = ts; currentSegDuration = sd; currentStartNumber = sn
                                currentInitTemplate = it; currentMediaTemplate = mt
                            } else {
                                timescale = ts; segmentDuration = sd; startNumber = sn
                                initTemplate = it; mediaTemplate = mt
                            }
                        }
                        "SegmentTimeline" -> inSegmentTimeline = true
                        "S" -> if (inSegmentTimeline) {
                            val t = xpp.getAttributeValue(null, "t")?.toLongOrNull()
                                ?: (currentTimelineSegments.lastOrNull()?.let { it.first + it.second } ?: 0L)
                            val d = xpp.getAttributeValue(null, "d")?.toLongOrNull() ?: 0L
                            val r = xpp.getAttributeValue(null, "r")?.toIntOrNull() ?: 0
                            var tCurrent = t
                            repeat(r + 1) {
                                currentTimelineSegments += tCurrent to d
                                tCurrent += d
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> when (xpp.name) {
                        "Representation" -> if (inVideoAdaptationSet && inRepresentation) {
                            representations += RepresentationInfo(
                                id = currentRepId,
                                bandwidth = currentRepBandwidth,
                                initTemplate = currentInitTemplate,
                                mediaTemplate = currentMediaTemplate,
                                timescale = currentTimescale,
                                segmentDuration = currentSegDuration,
                                startNumber = currentStartNumber
                            )
                            if (currentTimelineSegments.isNotEmpty()) {
                                timelineSegments.clear()
                                timelineSegments += currentTimelineSegments
                            }
                            inRepresentation = false
                        }
                        "AdaptationSet" -> { inAdaptationSet = false; inVideoAdaptationSet = false }
                        "SegmentTemplate" -> inSegmentTemplate = false
                        "SegmentTimeline" -> inSegmentTimeline = false
                    }
                }
                eventType = xpp.next()
            }

            // Pick the median-bandwidth representation (same policy as HLS variant selection).
            val rep = representations.sortedBy { it.bandwidth }.getOrNull(representations.size / 2)
                ?: throw IOException("No video representations found in MPD.")

            val resolvedInit: String? = rep.initTemplate.takeIf { it.isNotEmpty() }
                ?.replace("\$RepresentationID\$", rep.id)
                ?.replace("\$Bandwidth\$", rep.bandwidth.toString())
                ?.let { resolveRelativeUrl(baseUrl, it) }

            // A35 - the per-representation substitutions are constant for the whole manifest. Hoisting
            // them out of the per-segment loop removes two thirds of the string work from every poll:
            // the retention window can hold hundreds of segments and only $Number$ / $Time$ vary.
            val segmentTemplateBase = rep.mediaTemplate
                .replace("\$RepresentationID\$", rep.id)
                .replace("\$Bandwidth\$", rep.bandwidth.toString())

            val ts = rep.timescale.takeIf { it > 0L } ?: 1L
            val mediaSegments: List<RemoteHlsSegment> = if (timelineSegments.isNotEmpty()) {
                // SegmentTimeline mode: each (t, d) pair is one segment.
                timelineSegments.mapIndexed { index, (t, d) ->
                    val number = rep.startNumber + index
                    val uri = segmentTemplateBase
                        .replace("\$Number\$", number.toString())
                        .replace("\$Time\$", t.toString())
                        .let { resolveRelativeUrl(baseUrl, it) }
                    RemoteHlsSegment(uri = uri, durationMs = if (ts > 0L) d * 1000L / ts else d)
                }
            } else if (rep.segmentDuration > 0L) {
                // Fixed-duration mode: compute number from wall clock and availabilityStartTime.
                val nowMs = System.currentTimeMillis()
                val elapsedSecs = ((nowMs - availabilityStartTimeMs) / 1000L).coerceAtLeast(0L)
                val totalSegments = elapsedSecs * ts / rep.segmentDuration
                val windowSegments = (effectiveDepthMs / 1000L * ts / rep.segmentDuration + 2L).toInt()
                val firstNumber = (totalSegments - windowSegments).coerceAtLeast(rep.startNumber)
                (firstNumber..totalSegments).map { number ->
                    val uri = segmentTemplateBase
                        .replace("\$Number\$", number.toString())
                        .let { resolveRelativeUrl(baseUrl, it) }
                    RemoteHlsSegment(uri = uri, durationMs = rep.segmentDuration * 1000L / ts)
                }
            } else {
                emptyList()
            }

            return ParsedDashManifest(
                isDynamic = isDynamic,
                minimumUpdatePeriodMs = minimumUpdatePeriodMs,
                initSegmentUrl = resolvedInit,
                mediaSegments = mediaSegments
            )
        }

        /** Parses ISO 8601 duration strings like `PT2.5S`, `PT1M30S`, `P1DT2H`. */
        private fun parsePtDuration(value: String): Long {
            val upper = value.uppercase(Locale.ROOT)
            if (!upper.startsWith("P")) return 5_000L
            var ms = 0L
            val afterP = upper.removePrefix("P")
            val (datePart, timePart) = if (afterP.contains('T')) {
                afterP.substringBefore('T') to afterP.substringAfter('T')
            } else {
                afterP to ""
            }
            PT_DAYS_REGEX.find(datePart)?.groupValues?.get(1)?.toDoubleOrNull()?.let { ms += (it * 86_400_000).toLong() }
            PT_HOURS_REGEX.find(timePart)?.groupValues?.get(1)?.toDoubleOrNull()?.let { ms += (it * 3_600_000).toLong() }
            PT_MINUTES_REGEX.find(timePart)?.groupValues?.get(1)?.toDoubleOrNull()?.let { ms += (it * 60_000).toLong() }
            PT_SECONDS_REGEX.find(timePart)?.groupValues?.get(1)?.toDoubleOrNull()?.let { ms += (it * 1_000).toLong() }
            return ms.takeIf { it > 0L } ?: 5_000L
        }

        /** Parses an ISO 8601 datetime string to milliseconds since epoch, best-effort. */
        private fun parseIso8601ToMs(value: String): Long = runCatching {
            java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    private data class ParsedDashManifest(
        val isDynamic: Boolean,
        val minimumUpdatePeriodMs: Long,
        val initSegmentUrl: String?,
        val mediaSegments: List<RemoteHlsSegment>
    )

    private companion object {
        private const val PROGRESSIVE_CHUNK_MS = 2_000L
        private const val PROGRESSIVE_READ_BUFFER_SIZE = 65_536
        private const val MAX_PROGRESSIVE_RETRIES = 10
        private const val MAX_HLS_CONSECUTIVE_ERRORS = 10
        private const val MAX_DASH_CONSECUTIVE_ERRORS = 10
        private const val MAX_RETRY_DELAY_MS = 30_000L
        private const val HLS_ERROR_RETRY_DELAY_MS = 2_000L
        private const val DASH_ERROR_RETRY_DELAY_MS = 2_000L
        private const val MIN_FREE_DISK_BYTES = 200L * 1024 * 1024  // 200 MB
        private const val TIMESHIFT_PLAYBACK_THROTTLE_POLL_MS = 250L
    }
}
