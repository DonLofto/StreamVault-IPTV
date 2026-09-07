package com.streamvault.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RowScrollFixTest {

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun calculateRectForParent_usesRowHeight_preventingMicroScroll() {
        var height = 240f
        val responder = object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect {
                val effectiveHeight = if (height > 0f) height else localRect.height
                return Rect(localRect.left, 0f, localRect.right, effectiveHeight)
            }

            override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
        }

        // Child requests bring into view with local bounds within row
        val childRect = Rect(100f, 50f, 200f, 150f)
        val parentRect = responder.calculateRectForParent(childRect)

        // The vertical bounds cover the entire row (0 to 240), preserving horizontal position
        assertThat(parentRect.left).isEqualTo(100f)
        assertThat(parentRect.right).isEqualTo(200f)
        assertThat(parentRect.top).isEqualTo(0f)
        assertThat(parentRect.bottom).isEqualTo(240f)
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun calculateRectForParent_whenHeightUnset_fallsBackToLocalRectHeight() {
        val height = 0f
        val responder = object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect {
                val effectiveHeight = if (height > 0f) height else localRect.height
                return Rect(localRect.left, 0f, localRect.right, effectiveHeight)
            }

            override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
        }

        val childRect = Rect(50f, 10f, 150f, 90f)
        val parentRect = responder.calculateRectForParent(childRect)

        assertThat(parentRect.left).isEqualTo(50f)
        assertThat(parentRect.right).isEqualTo(150f)
        assertThat(parentRect.top).isEqualTo(0f)
        assertThat(parentRect.bottom).isEqualTo(80f)
    }
}
