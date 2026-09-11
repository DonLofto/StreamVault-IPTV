package com.streamvault.app.ui.screens.epg

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Program
import org.junit.Test

/**
 * A18 - a guide row composes only the programmes intersecting the visible time range. The row window
 * spans 7 h while roughly 3 h fits on screen, so before this the row composed about 2.3x the visible
 * cells for every visible channel row.
 */
class GuideRowWindowingTest {

    private val hour = 60L * 60L * 1000L
    private val windowStart = 1_700_000_000_000L

    private fun program(startOffsetHours: Int, lengthHours: Int = 1): Program = Program(
        id = startOffsetHours.toLong(),
        channelId = "channel-1",
        title = "Program $startOffsetHours",
        description = "",
        startTime = windowStart + startOffsetHours * hour,
        endTime = windowStart + (startOffsetHours + lengthHours) * hour,
        providerId = 1L
    )

    @Test
    fun `only the visible part of a seven hour window is composed`() {
        val programs = (0 until 7).map { program(it) }

        // 7 h of content in a 3 h viewport: total width is 7/3 of the viewport.
        val visible = programs.withinGuideRange(
            guideRowVisibleRangeMs(
                windowStart = windowStart,
                durationMs = 7 * hour,
                totalWidth = 700f,
                viewportWidth = 300f,
                scrollValue = 0,
                scrollMaxValue = 400
            )
        )

        // 0-3 h visible, plus the 30 minute overscan on each side. Hours 0..3 are in; 4..6 are out.
        assertThat(visible.map { it.id }).containsExactly(0L, 1L, 2L, 3L).inOrder()
        assertThat(visible.size).isLessThan(programs.size)
    }

    @Test
    fun `scrolling to the end composes the tail of the window`() {
        val programs = (0 until 7).map { program(it) }

        val visible = programs.withinGuideRange(
            guideRowVisibleRangeMs(
                windowStart = windowStart,
                durationMs = 7 * hour,
                totalWidth = 700f,
                viewportWidth = 300f,
                scrollValue = 400,
                scrollMaxValue = 400
            )
        )

        // 4-7 h visible with a 30 minute overscan below: hours 3..6.
        assertThat(visible.map { it.id }).containsExactly(3L, 4L, 5L, 6L).inOrder()
    }

    @Test
    fun `a window that fits on screen composes everything`() {
        val programs = (0 until 3).map { program(it) }

        val visible = programs.withinGuideRange(
            guideRowVisibleRangeMs(
                windowStart = windowStart,
                durationMs = 3 * hour,
                totalWidth = 300f,
                viewportWidth = 300f,
                scrollValue = 0,
                scrollMaxValue = 0
            )
        )

        assertThat(visible).isEqualTo(programs)
    }

    @Test
    fun `a programme straddling the visible edge is kept`() {
        val programmes = listOf(program(startOffsetHours = 0, lengthHours = 4))

        val visible = programmes.withinGuideRange(
            guideRowVisibleRangeMs(
                windowStart = windowStart,
                durationMs = 7 * hour,
                totalWidth = 700f,
                viewportWidth = 300f,
                scrollValue = 0,
                scrollMaxValue = 400
            )
        )

        assertThat(visible).isEqualTo(programmes)
    }
}
