package com.streamvault.app.startup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.streamvault.app.device.isTelevisionDevice
import com.streamvault.app.tv.LauncherRecommendationsManager
import com.streamvault.app.tv.WatchNextManager
import com.streamvault.app.tvinput.TvInputChannelSyncManager
import com.streamvault.app.update.GitHubReleaseChecker
import com.streamvault.data.manager.recording.RecordingReconcileWorker
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.sync.ProviderSyncWorker
import com.streamvault.data.sync.SyncWorker
import com.streamvault.data.sync.XtreamIndexWorker
import com.streamvault.domain.model.Result
import com.streamvault.player.timeshift.TimeshiftDiskManager
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StartupCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: Lazy<PreferencesRepository>,
    private val gitHubReleaseChecker: Lazy<GitHubReleaseChecker>,
    private val timeshiftDiskManager: Lazy<TimeshiftDiskManager>,
    private val watchNextManager: Lazy<WatchNextManager>,
    private val launcherRecommendationsManager: Lazy<LauncherRecommendationsManager>,
    private val tvInputChannelSyncManager: Lazy<TvInputChannelSyncManager>,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val deferredStarted = AtomicBoolean(false)
    private val updateCheckMutex = Mutex()

    fun onFirstFrameRendered(isTv: Boolean = context.isTelevisionDevice()) {
        if (!deferredStarted.compareAndSet(false, true)) return

        scope.launch {
            runCatching {
                timeshiftDiskManager.get().cleanupStaleDirectories(activeSessionDir = null)
            }
        }

        scope.launch {
            refreshCachedAppUpdateIfNeeded()
        }

        scheduleBackgroundWorkers()

        if (isTv) {
            scope.launch {
                runCatching { watchNextManager.get().refreshWatchNext() }
                runCatching { launcherRecommendationsManager.get().refreshRecommendations() }
                runCatching { tvInputChannelSyncManager.get().refreshTvInputCatalog() }
            }
        }
    }

    suspend fun refreshCachedAppUpdateIfNeeded() = updateCheckMutex.withLock {
        val prefs = preferencesRepository.get()
        val autoCheckEnabled = prefs.autoCheckAppUpdates.first()
        if (!autoCheckEnabled) return

        val lastCheckedAt = prefs.lastAppUpdateCheckTimestamp.first()
        val now = System.currentTimeMillis()
        val checkIntervalMs = 24L * 60L * 60L * 1000L
        if (lastCheckedAt != null && now - lastCheckedAt < checkIntervalMs) return

        prefs.setLastAppUpdateCheckTimestamp(now)
        when (val result = gitHubReleaseChecker.get().fetchLatestRelease()) {
            is Result.Success -> {
                prefs.setCachedAppUpdateRelease(
                    versionName = result.data.versionName,
                    versionCode = result.data.versionCode,
                    releaseUrl = result.data.releaseUrl,
                    downloadUrl = result.data.downloadUrl,
                    downloadSha256 = result.data.downloadSha256,
                    releaseNotes = result.data.releaseNotes,
                    publishedAt = result.data.publishedAt
                )
            }
            else -> Unit
        }
    }

    private fun scheduleBackgroundWorkers() {
        runCatching {
            val gcConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresDeviceIdle(true)
                .build()

            val gcWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(gcConstraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "DataMaintenanceWorker",
                ExistingPeriodicWorkPolicy.KEEP,
                gcWorkRequest
            )

            ProviderSyncWorker.enqueuePeriodic(context)
            ProviderSyncWorker.enqueueLaunchStaleCheck(context)
            XtreamIndexWorker.enqueuePeriodic(context)
            XtreamIndexWorker.enqueueLaunchStaleCheck(context)
            RecordingReconcileWorker.enqueuePeriodic(context)
            RecordingReconcileWorker.enqueueOneShot(context)
        }
    }

    fun isDeferredStarted(): Boolean = deferredStarted.get()
}
