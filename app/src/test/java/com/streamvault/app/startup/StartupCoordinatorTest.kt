package com.streamvault.app.startup

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.streamvault.app.tv.LauncherRecommendationsManager
import com.streamvault.app.tv.WatchNextManager
import com.streamvault.app.tvinput.TvInputChannelSyncManager
import com.streamvault.app.update.GitHubReleaseChecker
import com.streamvault.app.update.GitHubReleaseInfo
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Result
import com.streamvault.player.timeshift.TimeshiftDiskManager
import dagger.Lazy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class StartupCoordinatorTest {

    private val context: Context = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val gitHubReleaseChecker: GitHubReleaseChecker = mock()
    private val timeshiftDiskManager: TimeshiftDiskManager = mock()
    private val watchNextManager: WatchNextManager = mock()
    private val launcherRecommendationsManager: LauncherRecommendationsManager = mock()
    private val tvInputChannelSyncManager: TvInputChannelSyncManager = mock()

    private val testDispatcher = StandardTestDispatcher()

    private fun createCoordinator(): StartupCoordinator {
        return StartupCoordinator(
            context = context,
            preferencesRepository = Lazy { preferencesRepository },
            gitHubReleaseChecker = Lazy { gitHubReleaseChecker },
            timeshiftDiskManager = Lazy { timeshiftDiskManager },
            watchNextManager = Lazy { watchNextManager },
            launcherRecommendationsManager = Lazy { launcherRecommendationsManager },
            tvInputChannelSyncManager = Lazy { tvInputChannelSyncManager },
            ioDispatcher = testDispatcher
        )
    }

    @Before
    fun setup() {
        whenever(preferencesRepository.autoCheckAppUpdates).thenReturn(flowOf(false))
        whenever(preferencesRepository.lastAppUpdateCheckTimestamp).thenReturn(flowOf(null))
    }

    @Test
    fun onFirstFrameRendered_coalescesDuplicateCalls() = runTest(testDispatcher) {
        val coordinator = createCoordinator()

        coordinator.onFirstFrameRendered(isTv = true)
        coordinator.onFirstFrameRendered(isTv = true)

        advanceUntilIdle()

        assertThat(coordinator.isDeferredStarted()).isTrue()
        verify(timeshiftDiskManager, times(1)).cleanupStaleDirectories(null)
        verify(watchNextManager, times(1)).refreshWatchNext()
        verify(launcherRecommendationsManager, times(1)).refreshRecommendations()
        verify(tvInputChannelSyncManager, times(1)).refreshTvInputCatalog()
    }

    @Test
    fun onFirstFrameRendered_nonTv_doesNotRefreshTvManagers() = runTest(testDispatcher) {
        val coordinator = createCoordinator()

        coordinator.onFirstFrameRendered(isTv = false)
        advanceUntilIdle()

        verify(timeshiftDiskManager, times(1)).cleanupStaleDirectories(null)
        verify(watchNextManager, never()).refreshWatchNext()
        verify(launcherRecommendationsManager, never()).refreshRecommendations()
        verify(tvInputChannelSyncManager, never()).refreshTvInputCatalog()
    }

    @Test
    fun refreshCachedAppUpdateIfNeeded_fetchesWhenEnabledAndStale() = runTest(testDispatcher) {
        whenever(preferencesRepository.autoCheckAppUpdates).thenReturn(flowOf(true))
        whenever(preferencesRepository.lastAppUpdateCheckTimestamp).thenReturn(flowOf(0L))
        val release = GitHubReleaseInfo(
            versionName = "2.0.0",
            versionCode = 200,
            releaseUrl = "https://example.com/rel",
            downloadUrl = "https://example.com/rel.apk",
            downloadSha256 = "abc",
            releaseNotes = "Notes",
            publishedAt = "2026-01-01"
        )
        whenever(gitHubReleaseChecker.fetchLatestRelease()).thenReturn(Result.success(release))

        val coordinator = createCoordinator()
        coordinator.refreshCachedAppUpdateIfNeeded()

        verify(preferencesRepository).setCachedAppUpdateRelease(
            versionName = "2.0.0",
            versionCode = 200,
            releaseUrl = "https://example.com/rel",
            downloadUrl = "https://example.com/rel.apk",
            downloadSha256 = "abc",
            releaseNotes = "Notes",
            publishedAt = "2026-01-01"
        )
    }
}
