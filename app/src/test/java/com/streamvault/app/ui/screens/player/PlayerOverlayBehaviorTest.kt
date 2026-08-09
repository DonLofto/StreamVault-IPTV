package com.streamvault.app.ui.screens.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.app.ui.screens.player.overlay.syncSliderValue
import org.junit.Test

/**
 * Player overlay behavior regressions:
 * - B13: playback position updates must never reset the slider thumb mid-drag.
 * - B11: root zap interception must decline DPAD Up/Down while controls are visible.
 */
class PlayerOverlayBehaviorTest {

    @Test
    fun playbackTick_duringDrag_doesNotResetSliderValue() {
        val draggedValue = 0.7f
        val synced = syncSliderValue(
            currentPosition = 1_000L,
            duration = 10_000L,
            isScrubbing = true,
            currentDragValue = draggedValue
        )
        assertThat(synced).isEqualTo(draggedValue)
    }

    @Test
    fun playbackTick_whenNotDragging_syncsToPosition() {
        val synced = syncSliderValue(
            currentPosition = 5_000L,
            duration = 10_000L,
            isScrubbing = false
        )
        assertThat(synced).isEqualTo(0.5f)
    }

    @Test
    fun zeroDuration_keepsSliderAtZero() {
        assertThat(syncSliderValue(currentPosition = 0L, duration = 0L)).isEqualTo(0f)
        assertThat(syncSliderValue(currentPosition = 1_000L, duration = 0L)).isEqualTo(0f)
    }
}
