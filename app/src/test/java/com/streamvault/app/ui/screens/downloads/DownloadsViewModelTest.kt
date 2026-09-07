package com.streamvault.app.ui.screens.downloads

import android.content.Context
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import com.streamvault.app.MainDispatcherRule
import com.streamvault.app.R
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadRequest
import com.streamvault.domain.model.DownloadStatus
import com.streamvault.domain.model.DownloadStorageConfig
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.DownloadManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeDownloadManager : DownloadManager {
        val downloadsFlow = MutableStateFlow<List<DownloadItem>>(emptyList())
        val storageFlow = MutableStateFlow(DownloadStorageConfig())
        val cancelledIds = mutableListOf<String>()
        val resumedIds = mutableListOf<String>()
        val deletedIds = mutableListOf<String>()

        override fun observeAllDownloads(): Flow<List<DownloadItem>> = downloadsFlow
        override fun observeDownload(id: String): Flow<DownloadItem?> = flowOf(null)
        override fun observeStorageState(): Flow<DownloadStorageConfig> = storageFlow
        override suspend fun enqueueDownload(request: DownloadRequest): Result<DownloadItem> = Result.error("Not implemented")
        override suspend fun resumeDownload(id: String): Result<Unit> {
            resumedIds.add(id)
            return Result.success(Unit)
        }
        override suspend fun cancelDownload(id: String): Result<Unit> {
            cancelledIds.add(id)
            return Result.success(Unit)
        }
        override fun onPlaybackStarted() {}
        override fun onPlaybackStopped() {}
        override suspend fun deleteDownload(id: String): Result<Unit> {
            deletedIds.add(id)
            return Result.success(Unit)
        }
        override suspend fun updateStorageConfig(treeUri: String?, displayName: String?): Result<DownloadStorageConfig> =
            Result.success(DownloadStorageConfig())
    }

    private val fakeDownloadManager = FakeDownloadManager()
    private val context: Context = mock {
        on { getString(R.string.downloads_cancelled) } doReturn "Download cancelled"
        on { getString(R.string.downloads_resumed) } doReturn "Download resumed"
        on { getString(R.string.downloads_deleted) } doReturn "Download deleted"
    }

    private val sampleItem = DownloadItem(
        id = "dl-123",
        providerId = 1L,
        contentType = DownloadContentType.MOVIE,
        contentId = 42L,
        contentName = "Test Movie",
        streamUrl = "https://example.com/movie.mp4",
        status = DownloadStatus.DOWNLOADING
    )

    @Test
    fun `cancelDownload invokes downloadManager cancelDownload and sets message`() = runTest {
        val viewModel = DownloadsViewModel(fakeDownloadManager, context)

        viewModel.cancelDownload(sampleItem)

        assertEquals(listOf("dl-123"), fakeDownloadManager.cancelledIds)
        assertEquals("Download cancelled", viewModel.uiState.value.userMessage)
    }

    @Test
    fun `resumeDownload invokes downloadManager resumeDownload and sets message`() = runTest {
        val viewModel = DownloadsViewModel(fakeDownloadManager, context)

        viewModel.resumeDownload(sampleItem)

        assertEquals(listOf("dl-123"), fakeDownloadManager.resumedIds)
        assertEquals("Download resumed", viewModel.uiState.value.userMessage)
    }

    @Test
    fun `deleteDownload invokes downloadManager deleteDownload and sets message`() = runTest {
        val viewModel = DownloadsViewModel(fakeDownloadManager, context)

        viewModel.deleteDownload(sampleItem)

        assertEquals(listOf("dl-123"), fakeDownloadManager.deletedIds)
        assertEquals("Download deleted", viewModel.uiState.value.userMessage)
    }
}
