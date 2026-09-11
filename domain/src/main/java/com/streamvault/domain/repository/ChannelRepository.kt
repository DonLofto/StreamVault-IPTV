package com.streamvault.domain.repository

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import kotlinx.coroutines.flow.Flow

interface ChannelRepository {
    fun getChannels(providerId: Long): Flow<List<Channel>>
    fun getChannelCount(providerId: Long): Flow<Int>
    fun getChannelsByCategory(providerId: Long, categoryId: Long): Flow<List<Channel>>
    fun getChannelsByCategoryPage(providerId: Long, categoryId: Long, limit: Int): Flow<List<Channel>>
    fun getChannelsByNumber(providerId: Long, categoryId: Long = ALL_CHANNELS_ID): Flow<List<Channel>>
    fun getChannelsWithoutErrors(providerId: Long, categoryId: Long = ALL_CHANNELS_ID): Flow<List<Channel>>
    fun getChannelsWithoutErrorsPage(providerId: Long, categoryId: Long = ALL_CHANNELS_ID, limit: Int): Flow<List<Channel>>
    suspend fun getChannelsByCategoryPageOffset(providerId: Long, categoryId: Long, limit: Int, offset: Int): List<Channel>
    suspend fun getChannelsWithoutErrorsPageOffset(providerId: Long, categoryId: Long, limit: Int, offset: Int): List<Channel>
    fun searchChannelsByCategory(providerId: Long, categoryId: Long, query: String): Flow<List<Channel>>
    fun searchChannelsByCategoryPaged(providerId: Long, categoryId: Long, query: String, limit: Int): Flow<List<Channel>>
    fun getCategories(providerId: Long): Flow<List<Category>>
    fun searchChannels(providerId: Long, query: String): Flow<List<Channel>>

    /**
     * A17 - the channel universe a guide search may span, with the category-visibility filters
     * applied in SQL.
     *
     * This is deliberately the whole provider catalog rather than the paged guide listing: the grid
     * shows only the first page, but a search must find a channel anywhere in the provider. The two
     * filters are pure row predicates, so narrowing them in the query avoids walking every channel
     * in Kotlin on a path that re-runs on each debounced keystroke.
     *
     * A null category id is always kept, matching the previous Kotlin behaviour.
     */
    suspend fun getGuideSearchScopeChannels(
        providerId: Long,
        accessibleCategoryIds: Set<Long>,
        hiddenCategoryIds: Set<Long>
    ): List<Channel>
    suspend fun getChannel(channelId: Long): Channel?
    suspend fun getStreamInfo(channel: Channel, preferStableUrl: Boolean = false): Result<StreamInfo>
    suspend fun refreshChannels(providerId: Long): Result<Unit>
    fun getChannelsByIds(ids: List<Long>): Flow<List<Channel>>
    suspend fun incrementChannelErrorCount(channelId: Long): Result<Unit>
    suspend fun resetChannelErrorCount(channelId: Long): Result<Unit>

    companion object {
        const val ALL_CHANNELS_ID = -1_000_000L
    }
}
