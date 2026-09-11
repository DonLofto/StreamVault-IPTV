package com.streamvault.app.ui.screens.epg

import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.app.ui.theme.StreamVaultTheme
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A18 - a guide row composes only the programmes intersecting the visible time range.
 *
 * [GuideRowWindowingTest] covers the range arithmetic; this covers the wiring, by counting how many
 * programmes the row actually puts on screen for a seven hour window in a three hour viewport. Before
 * A18 all seven were composed for every visible channel row.
 */
@RunWith(AndroidJUnit4::class)
class EpgRowWindowCompositionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val hour = 60L * 60L * 1000L
    private val windowStart = 1_735_722_000_000L
    private val windowEnd = windowStart + 7 * hour

    private fun programmes(count: Int): List<Program> = (0 until count).map { index ->
        Program(
            id = index.toLong(),
            channelId = "channel-1",
            title = "Programme $index",
            description = "",
            startTime = windowStart + index * hour,
            endTime = windowStart + (index + 1) * hour,
            providerId = 1L
        )
    }

    private fun setRow(programmes: List<Program>) {
        composeRule.setContent {
            StreamVaultTheme {
                EpgRow(
                    channel = Channel(id = 1L, name = "Channel One", providerId = 1L, categoryId = 10L),
                    isFavorite = false,
                    programs = programmes,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    channelRailWidth = 200.dp,
                    timelineGap = 8.dp,
                    timelineViewportWidth = 300.dp,
                    totalTimelineWidth = 700.dp,
                    density = GuideDensity.COMPACT,
                    transparentOverlay = false,
                    rowHeight = 80.dp,
                    markerStepMs = hour,
                    scrollState = rememberScrollState(),
                    onChannelClick = {},
                    onChannelFocused = {},
                    onProgramClick = {},
                    onProgramFocused = {}
                )
            }
        }
    }

    private fun visibleProgrammeCount(title: String): Int =
        composeRule.onAllNodes(hasText(title)).fetchSemanticsNodes().size

    @Test
    fun rowComposesOnlyTheVisibleProgrammeWindow() {
        setRow(programmes(7))

        // A 3 h viewport over a 7 h window is 3/7 of the timeline. With the 30 minute overscan the
        // row must compose [windowStart - 30m, windowStart + 3h30m], which is programmes 0..3.
        assertThatCount("Programme 0", 1)
        assertThatCount("Programme 1", 1)
        assertThatCount("Programme 2", 1)
        assertThatCount("Programme 3", 1)

        // Everything beyond that must not be composed at all. Before A18 all seven were, for every
        // visible channel row.
        assertThatCount("Programme 4", 0)
        assertThatCount("Programme 5", 0)
        assertThatCount("Programme 6", 0)
    }

    @Test
    fun aWindowThatFitsOnScreenComposesEveryProgramme() {
        // Nothing to scroll, so no programme may be dropped.
        composeRule.setContent {
            StreamVaultTheme {
                EpgRow(
                    channel = Channel(id = 1L, name = "Channel One", providerId = 1L, categoryId = 10L),
                    isFavorite = false,
                    programs = programmes(3).map { it.copy(endTime = it.startTime + hour) },
                    windowStart = windowStart,
                    windowEnd = windowStart + 3 * hour,
                    channelRailWidth = 200.dp,
                    timelineGap = 8.dp,
                    timelineViewportWidth = 700.dp,
                    totalTimelineWidth = 700.dp,
                    density = GuideDensity.COMPACT,
                    transparentOverlay = false,
                    rowHeight = 80.dp,
                    markerStepMs = hour,
                    scrollState = rememberScrollState(),
                    onChannelClick = {},
                    onChannelFocused = {},
                    onProgramClick = {},
                    onProgramFocused = {}
                )
            }
        }

        assertThatCount("Programme 0", 1)
        assertThatCount("Programme 1", 1)
        assertThatCount("Programme 2", 1)
    }

    private fun assertThatCount(title: String, expected: Int) {
        composeRule.onAllNodes(hasText(title)).assertCountEquals(expected)
    }
}
