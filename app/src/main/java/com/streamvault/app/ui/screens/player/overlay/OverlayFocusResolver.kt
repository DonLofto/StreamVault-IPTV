package com.streamvault.app.ui.screens.player.overlay

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel

object OverlayFocusResolver {

    /**
     * Resolves the focused channel ID by identity, falling back to nearest neighbor by last known
     * index if the focused channel was removed, or fallbackId/first item.
     */
    fun resolveChannelFocus(
        channels: List<Channel>,
        lastFocusedId: Long?,
        lastKnownIndex: Int? = null,
        fallbackId: Long? = null
    ): Long? {
        if (channels.isEmpty()) return null
        if (lastFocusedId != null) {
            val found = channels.firstOrNull { it.id == lastFocusedId }
            if (found != null) return found.id
        }
        if (lastKnownIndex != null && lastKnownIndex >= 0) {
            val clampedIndex = lastKnownIndex.coerceIn(0, channels.lastIndex)
            return channels[clampedIndex].id
        }
        if (fallbackId != null && channels.any { it.id == fallbackId }) {
            return fallbackId
        }
        return channels.firstOrNull()?.id
    }

    /**
     * Resolves the focused category ID by identity, falling back to nearest neighbor by last known
     * index if the focused category was removed, or fallbackId/first item.
     */
    fun resolveCategoryFocus(
        categories: List<Category>,
        lastFocusedId: Long?,
        lastKnownIndex: Int? = null,
        fallbackId: Long? = null
    ): Long? {
        if (categories.isEmpty()) return null
        if (lastFocusedId != null) {
            val found = categories.firstOrNull { it.id == lastFocusedId }
            if (found != null) return found.id
        }
        if (lastKnownIndex != null && lastKnownIndex >= 0) {
            val clampedIndex = lastKnownIndex.coerceIn(0, categories.lastIndex)
            return categories[clampedIndex].id
        }
        if (fallbackId != null && categories.any { it.id == fallbackId }) {
            return fallbackId
        }
        return categories.firstOrNull()?.id
    }
}
