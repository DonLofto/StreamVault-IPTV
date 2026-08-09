package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.CatalogSyncDao
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.SeriesDao
import com.streamvault.data.local.dao.TmdbIdentityDao
import com.streamvault.data.local.entity.MovieEntity
import com.streamvault.data.local.entity.MovieImportStageEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * H3: catalog staging must not retain every remote key in heap. The store streams rows
 * through bounded batches; the assertion here verifies the peak in-memory staging buffer
 * is bounded by the batch size and independent of the total distinct row count.
 */
class SyncCatalogStoreMemoryTest {

    private val channelDao: ChannelDao = mock()
    private val movieDao: MovieDao = mock()
    private val seriesDao: SeriesDao = mock()
    private val categoryDao: CategoryDao = mock()
    private val catalogSyncDao: CatalogSyncDao = mock()
    private val tmdbIdentityDao: TmdbIdentityDao = mock()

    private fun store(sizeLimits: CatalogSizeLimits) = SyncCatalogStore(
        channelDao = channelDao,
        movieDao = movieDao,
        seriesDao = seriesDao,
        categoryDao = categoryDao,
        catalogSyncDao = catalogSyncDao,
        tmdbIdentityDao = tmdbIdentityDao,
        transactionRunner = object : DatabaseTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
        },
        sizeLimits = sizeLimits
    )

    @Before
    fun setup() {
        runBlocking {
            whenever(movieDao.getTmdbIdsByProvider(any())).thenReturn(emptyList())
            whenever(movieDao.getByProviderSync(any())).thenReturn(emptyList())
            whenever(catalogSyncDao.getMovieStages(any(), any())).thenReturn(emptyList())
        }
    }

    @Test
    fun replaceMovieCatalog_withLargeSyntheticCatalog_keepsPeakInMemoryBounded() = runTest {
        val providerId = 1L
        val totalRows = 250_000
        val limit = 200_000
        // DB reports all 250k distinct rows staged; store must trim to the 200k limit in SQL.
        whenever(catalogSyncDao.countMovieStages(eq(providerId), any())).thenReturn(totalRows)

        val limitedStore = store(CatalogSizeLimits(maxMoviesPerProvider = limit))
        val movies = generateSequence(0L) { it + 1 }
            .take(totalRows)
            .map { index ->
                MovieEntity(
                    streamId = index,
                    name = "Movie $index",
                    providerId = providerId,
                    streamUrl = "https://example.com/$index"
                )
            }

        val result = limitedStore.replaceMovieCatalog(providerId, categories = null, movies = movies)

        assertThat(result).isEqualTo(limit)

        val insertedStages = argumentCaptor<List<MovieImportStageEntity>>()
        verify(catalogSyncDao).insertMovieStages(insertedStages.capture())
        val allBatches = insertedStages.allValues
        val peakBatch = allBatches.maxOfOrNull { it.size } ?: 0
        val totalInserted = allBatches.sumOf { it.size }

        // Peak in-memory rows is the batch size, not the 250k distinct total.
        assertThat(peakBatch).isAtMost(500)
        assertThat(totalInserted).isEqualTo(totalRows)
        // Overflow is resolved in SQL by trimming the staged set to the limit.
        verify(catalogSyncDao).deleteMovieStagesBeyondTop(eq(providerId), any(), eq(limit))
    }
}
