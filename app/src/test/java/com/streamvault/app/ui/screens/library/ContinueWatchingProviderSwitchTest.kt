package com.streamvault.app.ui.screens.library

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.ContinueWatchingResult
import com.streamvault.domain.usecase.ContinueWatchingScope
import com.streamvault.domain.usecase.GetContinueWatching
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.app.ui.screens.movies.MoviesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * B9: continue-watching collection must be cancelled when the active provider changes,
 * so a stale provider's late emission can never overwrite the new provider's row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContinueWatchingProviderSwitchTest {

    private val providerRepository: ProviderRepository = mock()
    private val movieRepository: MovieRepository = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val playbackHistoryRepository: PlaybackHistoryRepository = mock()
    private val favoriteRepository: FavoriteRepository = mock()
    private val getContinueWatching: GetContinueWatching = mock()
    private val getCustomCategories: GetCustomCategories = mock()
    private val parentalControlManager: ParentalControlManager = mock()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.vodViewMode).thenReturn(flowOf(com.streamvault.app.ui.model.VodViewMode.MODERN.storageValue))
        whenever(preferencesRepository.vodInfiniteScroll).thenReturn(flowOf(false))
        whenever(preferencesRepository.getHiddenCategoryIds(any(), eq(ContentType.MOVIE))).thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getCategorySortMode(any(), eq(ContentType.MOVIE))).thenReturn(
            flowOf(com.streamvault.domain.model.CategorySortMode.DEFAULT)
        )
        whenever(getCustomCategories.invoke(any<Long>(), eq(ContentType.MOVIE))).thenReturn(flowOf(emptyList()))
        whenever(movieRepository.getCategories(any())).thenReturn(flowOf(emptyList()))
        whenever(movieRepository.getCategoryItemCounts(any())).thenReturn(flowOf(emptyMap()))
        whenever(movieRepository.getLibraryCount(any())).thenReturn(flowOf(0))
        whenever(favoriteRepository.getAllFavorites(any<Long>(), eq(ContentType.MOVIE))).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(any(), any())).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProviders(any(), any())).thenReturn(flowOf(emptyList()))
        whenever(movieRepository.getTopRatedPreview(any(), any())).thenReturn(flowOf(emptyList()))
        whenever(movieRepository.getFreshPreview(any(), any())).thenReturn(flowOf(emptyList()))
        whenever(movieRepository.getMoviesByIds(any())).thenReturn(flowOf(emptyList()))
        whenever(providerRepository.getProviders()).thenReturn(flowOf(emptyList()))
        whenever(parentalControlManager.unlockedCategoriesForProvider(any())).thenReturn(flowOf(emptySet()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun provider(id: Long, name: String = "Provider $id") = Provider(
        id = id,
        name = name,
        serverUrl = "https://provider$id.example.com",
        type = ProviderType.M3U
    )

    private fun history(providerId: Long, contentId: Long) = PlaybackHistory(
        contentId = contentId,
        contentType = ContentType.MOVIE,
        providerId = providerId,
        title = "Movie $contentId",
        streamUrl = "https://example.com/$contentId.m3u8"
    )

    private fun continueWatchingFlow(
        providerId: Long,
        items: List<PlaybackHistory>
    ) = flow {
        emit(ContinueWatchingResult.Items(items))
        kotlinx.coroutines.awaitCancellation()
    }

    @Test
    fun providerSwitch_cancelsOldCollectorAndKeepsNewProviderRows() = runTest {
        val activeProvider = MutableStateFlow<Provider?>(provider(1L))
        whenever(providerRepository.getActiveProvider()).thenReturn(activeProvider)

        val providerAItems = listOf(history(1L, 1L), history(1L, 2L))
        val providerBItems = listOf(history(2L, 3L))
        whenever(getContinueWatching.invoke(1L, 20, ContinueWatchingScope.MOVIES, false))
            .thenReturn(continueWatchingFlow(1L, providerAItems))

        val viewModel = MoviesViewModel(
            providerRepository = providerRepository,
            movieRepository = movieRepository,
            preferencesRepository = preferencesRepository,
            playbackHistoryRepository = playbackHistoryRepository,
            favoriteRepository = favoriteRepository,
            getContinueWatching = getContinueWatching,
            getCustomCategories = getCustomCategories,
            parentalControlManager = parentalControlManager
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.continueWatching.map { it.contentId })
            .containsExactly(1L, 2L)
            .inOrder()

        // Switch provider: A's collector must be cancelled.
        whenever(getContinueWatching.invoke(2L, 20, ContinueWatchingScope.MOVIES, false))
            .thenReturn(continueWatchingFlow(2L, providerBItems))
        activeProvider.value = provider(2L)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.continueWatching.map { it.contentId })
            .containsExactly(3L)

        // Provider A must never re-publish after the switch; its flow is cancelled.
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.continueWatching.map { it.contentId })
            .containsExactly(3L)
    }
}
