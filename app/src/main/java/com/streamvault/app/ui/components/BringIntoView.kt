package com.streamvault.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Scroll the minimum distance needed to reveal the focused child.
 *
 * Lazy lists on this Compose version park the focused child part-way up the viewport, so walking a
 * long list with the D-pad leaves the highlight pinned near the middle and the list scrolling under
 * it. On a TV list the highlight is the cursor: it should travel to the edge and only then push the
 * list.
 *
 * [BringIntoViewSpec] is consulted for every focused-child reveal, so returning zero while the child
 * is already fully inside the viewport keeps the list still, and otherwise returns exactly the
 * overshoot.
 */
internal val MinimalScrollBringIntoViewSpec: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailingEdge = offset + size
        return when {
            // Above the viewport: pull up by exactly the amount it is off by.
            offset < 0f -> offset
            // Below the viewport: push down by exactly the amount it overflows by.
            trailingEdge > containerSize -> trailingEdge - containerSize
            else -> 0f
        }
    }
}

/**
 * Applies [MinimalScrollBringIntoViewSpec] to everything below it.
 *
 * Use on a screen whose focusable lists should behave like a cursor. Compose reads the spec from
 * [LocalBringIntoViewSpec], so this covers the scrollable containers inside without having to
 * annotate each one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProvideMinimalBringIntoViewSpec(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScrollBringIntoViewSpec) {
        content()
    }
}
