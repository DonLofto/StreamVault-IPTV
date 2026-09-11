package com.streamvault.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.streamvault.app.diagnostics.CrashReportStore
import com.streamvault.app.diagnostics.RuntimeDiagnosticsManager
import com.streamvault.app.update.GitHubReleaseChecker
import com.streamvault.app.ui.accessibility.isReducedMotionEnabled
import com.streamvault.data.remote.jellyfin.JellyfinImageAuthInterceptor
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Result
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.streamvault.data.manager.recording.RecordingReconcileWorker
import com.streamvault.data.sync.ProviderSyncWorker
import com.streamvault.data.sync.XtreamIndexWorker
import com.streamvault.player.timeshift.TimeshiftDiskManager
import com.streamvault.player.cache.AppCacheQuota
import javax.inject.Inject
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean

@HiltAndroidApp
class StreamVaultApp : Application(), SingletonImageLoader.Factory {
    private val runtimeDiagnosticsManager by lazy { RuntimeDiagnosticsManager(this) }
    @Inject
    lateinit var startupCoordinator: com.streamvault.app.startup.StartupCoordinator

    // Deferred: field-injecting these built the whole network graph - OkHttp client plus two
    // DiskLruCache journals - during Application.onCreate, on the main thread, BEFORE the
    // startDeferredStartup() checkpoint that exists to keep exactly this off the cold-start path.
    @Inject
    lateinit var okHttpClient: dagger.Lazy<OkHttpClient>

    @Inject
    lateinit var jellyfinImageAuthInterceptor: JellyfinImageAuthInterceptor

    @Inject
    lateinit var appCacheQuota: dagger.Lazy<AppCacheQuota>

    private val imageOkHttpClient: OkHttpClient by lazy {
        okHttpClient.get().newBuilder()
            .addInterceptor(jellyfinImageAuthInterceptor)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        CrashReportStore.install(this)
        runtimeDiagnosticsManager.start()
    }

    /**
     * Starts optional maintenance only after the first activity frame has been submitted.
     * Keeping this behind an explicit checkpoint prevents application creation from becoming
     * part of the user-visible cold-start critical path.
     */
    fun startDeferredStartup() {
        startupCoordinator.onFirstFrameRendered()
    }

    override fun onTerminate() {
        runtimeDiagnosticsManager.stop()
        super.onTerminate()
    }


    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { imageOkHttpClient }
                    )
                )
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.15) // Conservative TV memory cache
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(appCacheQuota.get().budgets.imageCacheBytes)
                    .build()
            }
            // Limit concurrent decoding and fetching to 6 for TV hardware constraints
            .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(6))
            .decoderCoroutineContext(Dispatchers.Default.limitedParallelism(4))
            .crossfade(!isReducedMotionEnabled(context))
            .build()
    }
}
