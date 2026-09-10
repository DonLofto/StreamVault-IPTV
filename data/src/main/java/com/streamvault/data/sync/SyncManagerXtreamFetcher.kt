package com.streamvault.data.sync

import android.util.Log
import com.streamvault.data.remote.dto.XtreamCategory
import com.streamvault.data.remote.dto.XtreamLiveStreamRow
import com.streamvault.data.remote.dto.XtreamSeriesItem
import com.streamvault.data.remote.dto.XtreamStream
import com.streamvault.data.remote.xtream.LiveCategoryLoad
import com.streamvault.data.remote.xtream.OkHttpXtreamApiService
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.Series
import kotlin.system.measureTimeMillis

private const val XTREAM_FETCHER_TAG = "SyncManager"

internal class SyncManagerXtreamFetcher(
    private val xtreamCatalogApiService: XtreamApiService,
    private val xtreamCatalogHttpService: OkHttpXtreamApiService,
    private val xtreamSupport: SyncManagerXtreamSupport,
    private val sanitizeThrowableMessage: (Throwable?) -> String
) {
    suspend fun fetchLiveCategoryOutcome(
        provider: Provider,
        api: XtreamProvider,
        category: XtreamCategory,
        stageBatchSize: Int? = null,
        onMappedBatch: (suspend (List<Channel>) -> Unit)? = null
    ): TimedCategoryOutcome<Channel> {
        val endpoint = XtreamUrlFactory.buildPlayerApiUrl(
            serverUrl = provider.serverUrl,
            username = provider.username,
            password = provider.password,
            action = "get_live_streams",
            extraQueryParams = mapOf("category_id" to category.categoryId)
        )
        val batchSize = stageBatchSize?.takeIf { it > 0 }
        val mappedChannels = ArrayList<Channel>()
        val rawBatch = ArrayList<XtreamLiveStreamRow>(batchSize ?: 256)
        var rawCount = 0
        var categoryFailure: Throwable? = null

        suspend fun emitMappedChannels(channels: List<Channel>) {
            if (channels.isEmpty()) return
            if (onMappedBatch != null) {
                onMappedBatch(channels)
            } else {
                mappedChannels += channels
            }
        }

        suspend fun flushRawBatch() {
            if (rawBatch.isEmpty()) return
            emitMappedChannels(api.mapLiveStreamRowsSequence(rawBatch.asSequence()).toList())
            rawBatch.clear()
        }

        val elapsedMs = measureTimeMillis {
            when (val attempt = xtreamSupport.attemptNonCancellation {
                xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                    xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.CATEGORY) {
                        // Single HTTP request: thin rows are streamed to the batcher and, on
                        // thin decode failure, the same captured body is re-decoded with the
                        // legacy DTO — no second request for payloads within the buffer cap.
                        when (val load = xtreamCatalogHttpService.loadLiveCategory(
                            endpoint = endpoint,
                            onThinRow = { row ->
                                rawBatch += row
                                if (batchSize != null && rawBatch.size >= batchSize) {
                                    flushRawBatch()
                                }
                            }
                        )) {
                            is LiveCategoryLoad.Thin -> {
                                rawCount = load.rawCount
                                flushRawBatch()
                            }
                            is LiveCategoryLoad.Legacy -> {
                                rawCount = load.rawCount
                                // Discard any channels accumulated from a partial thin decode.
                                mappedChannels.clear()
                                emitMappedChannels(api.mapLiveStreamsResponse(load.streams))
                            }
                        }
                    }
                }
            }) {
                is Attempt.Success -> Unit
                is Attempt.Failure -> categoryFailure = attempt.error
            }
        }
        val outcome = when {
            categoryFailure != null -> {
                Log.w(
                    XTREAM_FETCHER_TAG,
                    "Xtream live category '${category.categoryName}' failed after ${elapsedMs}ms: ${sanitizeThrowableMessage(categoryFailure)}"
                )
                CategoryFetchOutcome.Failure(category.categoryName, categoryFailure!!)
            }
            rawCount == 0 -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream live category '${category.categoryName}' completed in ${elapsedMs}ms with a valid empty result."
                )
                CategoryFetchOutcome.Empty(category.categoryName)
            }
            else -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream live category '${category.categoryName}' completed in ${elapsedMs}ms with $rawCount raw items."
                )
                CategoryFetchOutcome.Success(category.categoryName, mappedChannels, rawCount)
            }
        }
        return TimedCategoryOutcome(category, outcome, elapsedMs)
    }

    suspend fun fetchMovieCategoryOutcome(
        provider: Provider,
        api: XtreamProvider,
        category: XtreamCategory
    ): TimedCategoryOutcome<Movie> {
        var rawStreams: List<XtreamStream> = emptyList()
        var categoryFailure: Throwable? = null
        val elapsedMs = measureTimeMillis {
            when (val attempt = xtreamSupport.attemptNonCancellation {
                xtreamSupport.withMovieRequestTimeout("movie category '${category.categoryName}'") {
                    xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                        xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.CATEGORY) {
                            xtreamCatalogApiService.getVodStreams(
                                XtreamUrlFactory.buildPlayerApiUrl(
                                    serverUrl = provider.serverUrl,
                                    username = provider.username,
                                    password = provider.password,
                                    action = "get_vod_streams",
                                    extraQueryParams = mapOf("category_id" to category.categoryId)
                                )
                            )
                        }
                    }
                }
            }) {
                is Attempt.Success -> rawStreams = attempt.value
                is Attempt.Failure -> categoryFailure = attempt.error
            }
        }
        val outcome = when {
            categoryFailure != null -> {
                Log.w(
                    XTREAM_FETCHER_TAG,
                    "Xtream movie category '${category.categoryName}' failed after ${elapsedMs}ms: ${sanitizeThrowableMessage(categoryFailure)}"
                )
                CategoryFetchOutcome.Failure(category.categoryName, categoryFailure!!)
            }
            rawStreams.isEmpty() -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream movie category '${category.categoryName}' completed in ${elapsedMs}ms with a valid empty result."
                )
                CategoryFetchOutcome.Empty(category.categoryName)
            }
            else -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream movie category '${category.categoryName}' completed in ${elapsedMs}ms with ${rawStreams.size} raw items."
                )
                CategoryFetchOutcome.Success(category.categoryName, api.mapVodStreamsResponse(rawStreams), rawStreams.size)
            }
        }
        return TimedCategoryOutcome(category, outcome, elapsedMs)
    }

    suspend fun fetchSeriesCategoryOutcome(
        provider: Provider,
        api: XtreamProvider,
        category: XtreamCategory
    ): TimedCategoryOutcome<Series> {
        var rawSeries: List<XtreamSeriesItem> = emptyList()
        var categoryFailure: Throwable? = null
        val elapsedMs = measureTimeMillis {
            when (val attempt = xtreamSupport.attemptNonCancellation {
                xtreamSupport.withSeriesRequestTimeout("series category '${category.categoryName}'") {
                    xtreamSupport.retryXtreamCatalogTransient(provider.id) {
                        xtreamSupport.executeXtreamRequest(provider.id, XtreamAdaptiveSyncPolicy.Stage.CATEGORY) {
                            xtreamCatalogApiService.getSeriesList(
                                XtreamUrlFactory.buildPlayerApiUrl(
                                    serverUrl = provider.serverUrl,
                                    username = provider.username,
                                    password = provider.password,
                                    action = "get_series",
                                    extraQueryParams = mapOf("category_id" to category.categoryId)
                                )
                            )
                        }
                    }
                }
            }) {
                is Attempt.Success -> rawSeries = attempt.value
                is Attempt.Failure -> categoryFailure = attempt.error
            }
        }
        val outcome = when {
            categoryFailure != null -> {
                Log.w(
                    XTREAM_FETCHER_TAG,
                    "Xtream series category '${category.categoryName}' failed after ${elapsedMs}ms: ${sanitizeThrowableMessage(categoryFailure)}"
                )
                CategoryFetchOutcome.Failure(category.categoryName, categoryFailure!!)
            }
            rawSeries.isEmpty() -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream series category '${category.categoryName}' completed in ${elapsedMs}ms with a valid empty result."
                )
                CategoryFetchOutcome.Empty(category.categoryName)
            }
            else -> {
                Log.i(
                    XTREAM_FETCHER_TAG,
                    "Xtream series category '${category.categoryName}' completed in ${elapsedMs}ms with ${rawSeries.size} raw items."
                )
                CategoryFetchOutcome.Success(category.categoryName, api.mapSeriesListResponse(rawSeries), rawSeries.size)
            }
        }
        return TimedCategoryOutcome(category, outcome, elapsedMs)
    }

}
