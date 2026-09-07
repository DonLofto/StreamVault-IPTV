package com.streamvault.app.ui.screens.player.overlay

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import org.junit.Test

class OverlayFocusResolverTest {

    private fun testChannel(id: Long, name: String = "Channel $id"): Channel {
        return Channel(
            id = id,
            name = name,
            streamUrl = "http://example.com/$id",
            streamId = id
        )
    }

    private fun testCategory(id: Long, name: String = "Category $id"): Category {
        return Category(
            id = id,
            name = name
        )
    }

    @Test
    fun channelFocus_insertionBeforeFocusedItem_preservesIdentity() {
        val ch1 = testChannel(10)
        val ch2 = testChannel(20)
        val ch3 = testChannel(30)
        val initialList = listOf(ch1, ch2, ch3)

        // Focused on ch2 (id=20)
        val focusedId = 20L

        // Insert new channel at start
        val newCh0 = testChannel(5)
        val updatedList = listOf(newCh0, ch1, ch2, ch3)

        val resolved = OverlayFocusResolver.resolveChannelFocus(
            channels = updatedList,
            lastFocusedId = focusedId,
            lastKnownIndex = 1,
            fallbackId = 10L
        )

        assertThat(resolved).isEqualTo(20L)
    }

    @Test
    fun channelFocus_reorder_preservesIdentity() {
        val ch1 = testChannel(10)
        val ch2 = testChannel(20)
        val ch3 = testChannel(30)
        val initialList = listOf(ch1, ch2, ch3)

        val focusedId = 20L

        // Reverse list order
        val reorderedList = listOf(ch3, ch2, ch1)

        val resolved = OverlayFocusResolver.resolveChannelFocus(
            channels = reorderedList,
            lastFocusedId = focusedId,
            lastKnownIndex = 1,
            fallbackId = 10L
        )

        assertThat(resolved).isEqualTo(20L)
    }

    @Test
    fun channelFocus_removalOfFocusedItem_fallsBackToNearestNeighbor() {
        val ch1 = testChannel(10)
        val ch2 = testChannel(20)
        val ch3 = testChannel(30)
        val ch4 = testChannel(40)

        // Focused on ch3 (index 2, id 30)
        val focusedId = 30L
        val lastIndex = 2

        // ch3 is removed
        val updatedList = listOf(ch1, ch2, ch4)

        // Clamped index 2 in updatedList is ch4 (index 2)
        val resolved = OverlayFocusResolver.resolveChannelFocus(
            channels = updatedList,
            lastFocusedId = focusedId,
            lastKnownIndex = lastIndex,
            fallbackId = 10L
        )

        assertThat(resolved).isEqualTo(40L)
    }

    @Test
    fun categoryFocus_refresh_preservesIdentity() {
        val cat1 = testCategory(1)
        val cat2 = testCategory(2)
        val cat3 = testCategory(3)

        val focusedId = 2L

        // Refreshed categories with updated names
        val refreshed = listOf(
            testCategory(1, "Updated 1"),
            testCategory(2, "Updated 2"),
            testCategory(3, "Updated 3")
        )

        val resolved = OverlayFocusResolver.resolveCategoryFocus(
            categories = refreshed,
            lastFocusedId = focusedId,
            lastKnownIndex = 1,
            fallbackId = 1L
        )

        assertThat(resolved).isEqualTo(2L)
    }

    @Test
    fun categoryFocus_restorationAfterCloseAndReopen() {
        val categories = listOf(testCategory(1), testCategory(2), testCategory(3))

        // When closed, user was on category 3
        val savedFocusedId = 3L

        // Reopening with saved state
        val resolved = OverlayFocusResolver.resolveCategoryFocus(
            categories = categories,
            lastFocusedId = savedFocusedId,
            lastKnownIndex = 2,
            fallbackId = 1L
        )

        assertThat(resolved).isEqualTo(3L)
    }
}
