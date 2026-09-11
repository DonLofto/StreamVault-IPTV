package com.streamvault.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.ProviderType
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A17 - [ChannelDao.getGuideSearchScopeChannels] pushes the two category-visibility filters into
 * SQL. This runs against a real SQLite database rather than a mock, because the whole point of the
 * change is the query: the null-category branch and the IN / NOT IN pair cannot be checked any
 * other way.
 */
@RunWith(AndroidJUnit4::class)
class ChannelGuideScopeDaoTest {

    private lateinit var db: StreamVaultDatabase
    private lateinit var channelDao: ChannelDao

    private val sentinel = Long.MIN_VALUE

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StreamVaultDatabase::class.java).build()
        channelDao = db.channelDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    private fun channel(streamId: Long, categoryId: Long?): ChannelEntity = ChannelEntity(
        streamId = streamId,
        name = "Channel $streamId",
        categoryId = categoryId,
        providerId = PROVIDER_ID,
        number = streamId.toInt()
    )

    private suspend fun seed() {
        db.providerDao().insert(
            ProviderEntity(
                id = PROVIDER_ID,
                name = "Provider",
                type = ProviderType.M3U,
                serverUrl = "https://provider.example.test"
            )
        )
        channelDao.insertAll(
            listOf(
                channel(streamId = 1L, categoryId = 10L),
                channel(streamId = 2L, categoryId = 11L),
                channel(streamId = 3L, categoryId = 99L),
                channel(streamId = 4L, categoryId = null)
            )
        )
    }

    @Test
    fun accessibleAndHiddenFiltersMatchTheKotlinPredicatesTheyReplaced() = runTest {
        seed()

        // Everything accessible, nothing hidden: a channel with no category is always kept.
        val unrestricted = channelDao.getGuideSearchScopeChannels(
            providerId = PROVIDER_ID,
            accessibleCategoryIds = listOf(10L, 11L, sentinel),
            hiddenCategoryIds = listOf(sentinel)
        )
        assertThat(unrestricted.map { it.streamId }).containsExactly(1L, 2L, 4L).inOrder()

        // 11 hidden: it drops out even though it is accessible. 99 is not accessible either.
        val withHidden = channelDao.getGuideSearchScopeChannels(
            providerId = PROVIDER_ID,
            accessibleCategoryIds = listOf(10L, 11L, sentinel),
            hiddenCategoryIds = listOf(11L, sentinel)
        )
        assertThat(withHidden.map { it.streamId }).containsExactly(1L, 4L).inOrder()

        // Nothing accessible: only the null-category channel survives, matching
        // "categoryId == null || categoryId in accessibleCategoryIds".
        val nothingAccessible = channelDao.getGuideSearchScopeChannels(
            providerId = PROVIDER_ID,
            accessibleCategoryIds = listOf(sentinel),
            hiddenCategoryIds = listOf(sentinel)
        )
        assertThat(nothingAccessible.map { it.streamId }).containsExactly(4L)
    }

    @Test
    fun scopeIsScopedToTheRequestedProvider() = runTest {
        seed()
        db.providerDao().insert(
            ProviderEntity(
                id = 8L,
                name = "Other",
                type = ProviderType.M3U,
                serverUrl = "https://other.example.test"
            )
        )
        channelDao.insertAll(listOf(channel(streamId = 5L, categoryId = 10L).copy(providerId = 8L)))

        val scope = channelDao.getGuideSearchScopeChannels(
            providerId = PROVIDER_ID,
            accessibleCategoryIds = listOf(10L, sentinel),
            hiddenCategoryIds = listOf(sentinel)
        )

        assertThat(scope.map { it.streamId }).containsExactly(1L, 4L).inOrder()
    }

    private companion object {
        const val PROVIDER_ID = 7L
    }
}
