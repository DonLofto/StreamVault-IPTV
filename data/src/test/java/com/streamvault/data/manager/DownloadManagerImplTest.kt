package com.streamvault.data.manager

import android.content.Context
import android.net.ConnectivityManager
import com.streamvault.data.local.dao.DownloadDao
import com.streamvault.data.local.entity.DownloadEntity
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadStatus
import com.streamvault.domain.model.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DownloadManagerImplTest {

    private val context: Context = mock()
    private val downloadDao: DownloadDao = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val okHttpClient: OkHttpClient = mock()
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver = mock()
    private val connectivityManager: ConnectivityManager = mock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    init {
        whenever(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(connectivityManager)
    }

    @Test
    fun `cancelDownload updates entity to CANCELLED and resets output state`() = runBlocking {
        val manager = DownloadManagerImpl(
            context = context,
            downloadDao = downloadDao,
            preferencesRepository = preferencesRepository,
            okHttpClient = okHttpClient,
            xtreamStreamUrlResolver = xtreamStreamUrlResolver,
            applicationScope = scope
        )

        val activeEntity = DownloadEntity(
            id = "dl-1",
            providerId = 10L,
            contentType = DownloadContentType.MOVIE,
            contentId = 100L,
            contentName = "Test Movie",
            streamUrl = "https://example.com/movie.mp4",
            status = DownloadStatus.DOWNLOADING,
            bytesWritten = 1024L,
            outputUri = "content://downloads/1",
            outputDisplayPath = "/tmp/test.mp4"
        )

        whenever(downloadDao.getByIdOnce("dl-1")).thenReturn(activeEntity)
        whenever(downloadDao.getQueuedOnce()).thenReturn(emptyList())

        val result = manager.cancelDownload("dl-1")

        assertTrue(result is Result.Success)

        val captor = argumentCaptor<DownloadEntity>()
        verify(downloadDao).update(captor.capture())

        val updated = captor.firstValue
        assertEquals(DownloadStatus.CANCELLED, updated.status)
        assertEquals(0L, updated.bytesWritten)
        assertEquals(null, updated.outputUri)
        assertEquals(null, updated.outputDisplayPath)
    }
}
