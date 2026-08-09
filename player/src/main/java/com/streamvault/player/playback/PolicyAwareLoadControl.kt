package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.Allocation
import androidx.media3.exoplayer.upstream.Allocator.AllocationNode
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.PlayerIdAwareAllocator
import java.util.concurrent.ConcurrentHashMap

/**
 * A [LoadControl] that reads its buffer sizes from a mutable [PlaybackBufferPolicy].
 *
 * Unlike `DefaultLoadControl`, whose buffer durations are fixed at construction and can therefore
 * only be changed by tearing the [androidx.media3.exoplayer.ExoPlayer] down, this control keeps its
 * own per-player loading state and reads `min/max/playback/rebuffer` values from the current policy
 * on every decision. [updatePolicy] swaps the sizing in place, so a live buffer promotion
 * (e.g. `stable-live` -> `large-live`) never requires a player re-creation.
 *
 * Behavior mirrors `androidx.media3.exoplayer.DefaultLoadControl` for the settings this app
 * configures: the builder-supplied `DefaultAllocator`, per-track target sizes, live-offset
 * capping and local-playback defaults.
 */
@UnstableApi
internal class PolicyAwareLoadControl(
    initialPolicy: PlaybackBufferPolicy
) : LoadControl {

    private val allocator = DefaultAllocator(/* trimOnReset */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
    private val loadingStates = ConcurrentHashMap<PlayerId, PlayerLoadingState>()
    private var threadId: Long = C.INDEX_UNSET.toLong()

    private val window = Timeline.Window()
    private val period = Timeline.Period()

    private var minBufferMs: Long = initialPolicy.minBufferMs.toLong()
    private var maxBufferMs: Long = initialPolicy.maxBufferMs.toLong()
    private var bufferForPlaybackMs: Long = initialPolicy.playbackBufferMs.toLong()
    private var bufferForPlaybackAfterRebufferMs: Long = initialPolicy.rebufferMs.toLong()
    private var targetBufferBytesOverwrite: Int = initialPolicy.targetBufferBytes
    private var prioritizeTimeOverSizeThresholds: Boolean =
        initialPolicy.prioritizeTimeOverSizeThresholds

    fun updatePolicy(policy: PlaybackBufferPolicy) {
        minBufferMs = policy.minBufferMs.toLong()
        maxBufferMs = policy.maxBufferMs.toLong()
        bufferForPlaybackMs = policy.playbackBufferMs.toLong()
        bufferForPlaybackAfterRebufferMs = policy.rebufferMs.toLong()
        targetBufferBytesOverwrite = policy.targetBufferBytes
        prioritizeTimeOverSizeThresholds = policy.prioritizeTimeOverSizeThresholds
        if (policy.targetBufferBytes != C.LENGTH_UNSET) {
            // Reflect an explicit per-policy byte target without waiting for a track re-selection.
            loadingStates.forEach { (_, state) ->
                state.targetBufferBytes = policy.targetBufferBytes
            }
        }
        updateAllocator()
    }

    override fun onPrepared(playerId: PlayerId) {
        val currentThreadId = Thread.currentThread().id
        check(
            threadId == C.INDEX_UNSET.toLong() || threadId == currentThreadId
        ) {
            "Players that share the same LoadControl must share the same playback thread."
        }
        threadId = currentThreadId
        val playerLoadingState = loadingStates[playerId]
        if (playerLoadingState == null) {
            loadingStates[playerId] = PlayerLoadingState()
        } else {
            playerLoadingState.referenceCount++
        }
        resetPlayerLoadingState(playerId)
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>
    ) {
        val playerLoadingState = loadingStates[parameters.playerId] ?: return
        val trackTarget = getTargetBufferBytesOverwrite(parameters.playerId)
        playerLoadingState.targetBufferBytes = if (trackTarget == C.LENGTH_UNSET) {
            calculateTargetBufferBytes(parameters, trackSelections)
        } else {
            trackTarget
        }
        updateAllocator()
    }

    override fun onStopped(playerId: PlayerId) {
        removePlayer(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        removePlayer(playerId)
        if (loadingStates.isEmpty()) {
            threadId = C.INDEX_UNSET.toLong()
        }
    }

    override fun getAllocator(playerId: PlayerId): Allocator =
        PlayerIdFilteringAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = 0L

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = false

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val playerId = parameters.playerId
        val playerLoadingState = loadingStates[playerId] ?: return false
        val targetBufferSizeReached =
            getTotalBufferBytesAllocated(playerId) >= getTargetBufferBytes(playerId)
        if (playerId == PlayerId.PRELOAD) {
            return !targetBufferSizeReached
        }
        val isLocalPlayback = isLocalPlayback(parameters)
        var minBufferUs = getMinBufferUs(isLocalPlayback)
        val maxBufferUs = getMaxBufferUs(isLocalPlayback)
        if (parameters.playbackSpeed > 1) {
            val mediaDurationMinBufferUs =
                Util.getMediaDurationForPlayoutDuration(minBufferUs, parameters.playbackSpeed)
            minBufferUs = minOf(mediaDurationMinBufferUs, maxBufferUs)
        }
        // Prevent playback from getting stuck if minBufferUs is too small.
        minBufferUs = maxOf(minBufferUs, 500_000)
        if (parameters.bufferedDurationUs < minBufferUs) {
            playerLoadingState.isLoading =
                prioritizeTimeOverSizeThresholds(isLocalPlayback) || !targetBufferSizeReached
        } else if (parameters.bufferedDurationUs >= maxBufferUs || targetBufferSizeReached) {
            playerLoadingState.isLoading = false
        }
        return playerLoadingState.isLoading
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val isLocalPlayback = isLocalPlayback(parameters)
        val bufferedDurationUs = Util.getPlayoutDurationForMediaDuration(
            parameters.bufferedDurationUs,
            parameters.playbackSpeed
        )
        var minBufferDurationUs = if (parameters.rebuffering) {
            getBufferForPlaybackAfterRebufferUs(isLocalPlayback)
        } else {
            getBufferForPlaybackUs(isLocalPlayback)
        }
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) {
            minBufferDurationUs = minOf(parameters.targetLiveOffsetUs / 2, minBufferDurationUs)
        }
        return minBufferDurationUs <= 0 ||
            bufferedDurationUs >= minBufferDurationUs ||
            (!prioritizeTimeOverSizeThresholds(isLocalPlayback) &&
                getTotalBufferBytesAllocated(parameters.playerId) >=
                getTargetBufferBytes(parameters.playerId))
    }

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long
    ): Boolean {
        for (loadingState in loadingStates.values) {
            if (loadingState.isLoading) {
                return false
            }
        }
        return true
    }

    private fun resetPlayerLoadingState(playerId: PlayerId) {
        val playerLoadingState = loadingStates[playerId] ?: return
        val targetBufferBytesOverwrite = getTargetBufferBytesOverwrite(playerId)
        playerLoadingState.targetBufferBytes = if (targetBufferBytesOverwrite == C.LENGTH_UNSET) {
            DEFAULT_MIN_BUFFER_SIZE
        } else {
            targetBufferBytesOverwrite
        }
        playerLoadingState.isLoading = false
    }

    private fun getTargetBufferBytesOverwrite(playerId: PlayerId): Int = targetBufferBytesOverwrite

    private fun removePlayer(playerId: PlayerId) {
        val playerLoadingState = loadingStates[playerId] ?: return
        playerLoadingState.referenceCount--
        if (playerLoadingState.referenceCount == 0) {
            loadingStates.remove(playerId)
            updateAllocator()
        }
    }

    private fun updateAllocator() {
        if (loadingStates.isEmpty()) {
            allocator.reset()
        } else {
            allocator.setTargetBufferSize(calculateTotalTargetBufferBytes())
        }
    }

    private fun calculateTargetBufferBytes(
        parameters: LoadControl.Parameters,
        trackSelections: Array<ExoTrackSelection?>
    ): Int {
        var targetBufferBytes = 0
        val isLocalPlayback = isLocalPlayback(parameters)
        for (trackSelection in trackSelections) {
            if (trackSelection != null) {
                targetBufferBytes +=
                    getDefaultBufferSize(
                        trackSelection.trackGroup.type,
                        isLocalPlayback
                    )
            }
        }
        return Util.constrainValue(
            targetBufferBytes,
            DEFAULT_MIN_BUFFER_SIZE,
            DEFAULT_MAX_BUFFER_SIZE
        )
    }

    private fun calculateTotalTargetBufferBytes(): Int {
        var totalTargetBufferBytes = 0
        loadingStates.forEach { (_, state) ->
            totalTargetBufferBytes += state.targetBufferBytes
        }
        return totalTargetBufferBytes
    }

    private fun getTotalBufferBytesAllocated(playerId: PlayerId): Int {
        val loadingState = loadingStates[playerId] ?: return 0
        return loadingState.readAllocatedCounts() * allocator.getIndividualAllocationLength()
    }

    private fun getTargetBufferBytes(playerId: PlayerId): Int {
        return loadingStates[playerId]?.targetBufferBytes ?: C.LENGTH_UNSET
    }

    private fun isLocalPlayback(parameters: LoadControl.Parameters): Boolean {
        val mediaPeriodId = parameters.mediaPeriodId ?: return false
        return try {
            val windowIndex = parameters.timeline
                .getPeriodByUid(mediaPeriodId.periodUid, period)
                .windowIndex
            val mediaItem = parameters.timeline.getWindow(windowIndex, window).mediaItem
            val localConfiguration = mediaItem.localConfiguration ?: return false
            val scheme = localConfiguration.uri.scheme
            scheme.isNullOrEmpty() || scheme in LOCAL_PLAYBACK_SCHEMES
        } catch (_: IndexOutOfBoundsException) {
            // Timeline does not contain this period yet (e.g. Timeline.EMPTY): treat as remote.
            false
        }
    }

    private fun getMinBufferUs(isLocalPlayback: Boolean): Long =
        if (isLocalPlayback) MIN_BUFFER_FOR_LOCAL_PLAYBACK_US else minBufferMs * 1000L

    private fun getMaxBufferUs(isLocalPlayback: Boolean): Long =
        if (isLocalPlayback) MAX_BUFFER_FOR_LOCAL_PLAYBACK_US else maxBufferMs * 1000L

    private fun getBufferForPlaybackUs(isLocalPlayback: Boolean): Long =
        if (isLocalPlayback) BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_US else bufferForPlaybackMs * 1000L

    private fun getBufferForPlaybackAfterRebufferUs(isLocalPlayback: Boolean): Long =
        if (isLocalPlayback) BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_US
        else bufferForPlaybackAfterRebufferMs * 1000L

    private fun prioritizeTimeOverSizeThresholds(isLocalPlayback: Boolean): Boolean =
        if (isLocalPlayback) PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS_FOR_LOCAL_PLAYBACK
        else prioritizeTimeOverSizeThresholds

    private fun getDefaultBufferSize(trackType: Int, isLocalPlayback: Boolean): Int = when (
        trackType
    ) {
        C.TRACK_TYPE_DEFAULT -> DEFAULT_MUXED_BUFFER_SIZE
        C.TRACK_TYPE_AUDIO -> DEFAULT_AUDIO_BUFFER_SIZE
        C.TRACK_TYPE_VIDEO -> if (isLocalPlayback) DEFAULT_VIDEO_BUFFER_SIZE_FOR_LOCAL_PLAYBACK
        else DEFAULT_VIDEO_BUFFER_SIZE
        C.TRACK_TYPE_TEXT -> DEFAULT_TEXT_BUFFER_SIZE
        C.TRACK_TYPE_METADATA -> DEFAULT_METADATA_BUFFER_SIZE
        C.TRACK_TYPE_CAMERA_MOTION -> DEFAULT_CAMERA_MOTION_BUFFER_SIZE
        C.TRACK_TYPE_IMAGE -> DEFAULT_IMAGE_BUFFER_SIZE
        C.TRACK_TYPE_NONE -> 0
        C.TRACK_TYPE_UNKNOWN -> DEFAULT_MIN_BUFFER_SIZE
        else -> throw IllegalArgumentException("Unsupported track type: $trackType")
    }

    private inner class PlayerIdFilteringAllocator(
        private var playerId: PlayerId
    ) : PlayerIdAwareAllocator {

        private val allocationPlayerIdMap = HashMap<Allocation, PlayerId>()

        override fun setPlayerId(playerId: PlayerId) {
            this.playerId = playerId
        }

        override fun allocate(): Allocation {
            val allocation = allocator.allocate()
            allocationPlayerIdMap[allocation] = playerId
            loadingStates[playerId]?.increaseAllocatedCounts()
            return allocation
        }

        override fun release(allocation: Allocation) {
            allocator.release(allocation)
            releaseInternal(allocation)
        }

        override fun release(allocationNode: AllocationNode) {
            allocator.release(allocationNode)
            var node: AllocationNode? = allocationNode
            while (node != null) {
                releaseInternal(node.allocation)
                node = node.next()
            }
        }

        override fun trim() {
            allocator.trim()
        }

        override fun getTotalBytesAllocated(): Int = getTotalBufferBytesAllocated(playerId)

        override fun getIndividualAllocationLength(): Int =
            allocator.getIndividualAllocationLength()

        private fun releaseInternal(allocation: Allocation) {
            val owner = allocationPlayerIdMap.remove(allocation)
            if (owner != null) {
                loadingStates[owner]?.decreaseAllocatedCounts()
            }
        }
    }

    private class PlayerLoadingState {
        var referenceCount = 1
        var isLoading = false
        var targetBufferBytes = 0
        var allocatedCounts = 0

        @Synchronized
        fun increaseAllocatedCounts() {
            allocatedCounts++
        }

        @Synchronized
        fun decreaseAllocatedCounts() {
            allocatedCounts--
        }

        @Synchronized
        fun readAllocatedCounts(): Int = allocatedCounts
    }

    private companion object {
        const val MIN_BUFFER_FOR_LOCAL_PLAYBACK_US = 1_000_000L
        const val MAX_BUFFER_FOR_LOCAL_PLAYBACK_US = 50_000_000L
        const val BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_US = 1_000_000L
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_US = 1_000_000L
        const val PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS_FOR_LOCAL_PLAYBACK = true

        val DEFAULT_VIDEO_BUFFER_SIZE = 2000 * C.DEFAULT_BUFFER_SEGMENT_SIZE
        val DEFAULT_VIDEO_BUFFER_SIZE_FOR_LOCAL_PLAYBACK = 300 * C.DEFAULT_BUFFER_SEGMENT_SIZE
        val DEFAULT_AUDIO_BUFFER_SIZE = 200 * C.DEFAULT_BUFFER_SEGMENT_SIZE
        val DEFAULT_TEXT_BUFFER_SIZE = 2 * C.DEFAULT_BUFFER_SEGMENT_SIZE
        val DEFAULT_METADATA_BUFFER_SIZE = 2 * C.DEFAULT_BUFFER_SEGMENT_SIZE
        val DEFAULT_CAMERA_MOTION_BUFFER_SIZE = 2 * C.DEFAULT_BUFFER_SEGMENT_SIZE
        val DEFAULT_IMAGE_BUFFER_SIZE = 400 * C.DEFAULT_BUFFER_SEGMENT_SIZE
        val DEFAULT_MUXED_BUFFER_SIZE =
            DEFAULT_VIDEO_BUFFER_SIZE + DEFAULT_AUDIO_BUFFER_SIZE + DEFAULT_TEXT_BUFFER_SIZE
        val DEFAULT_MIN_BUFFER_SIZE = 200 * C.DEFAULT_BUFFER_SEGMENT_SIZE
        val DEFAULT_MAX_BUFFER_SIZE =
            DEFAULT_VIDEO_BUFFER_SIZE +
                4 * DEFAULT_AUDIO_BUFFER_SIZE +
                4 * DEFAULT_TEXT_BUFFER_SIZE +
                DEFAULT_IMAGE_BUFFER_SIZE

        val LOCAL_PLAYBACK_SCHEMES = setOf(
            "file",
            "content",
            "data",
            "android.resource",
            "rawresource",
            "asset"
        )
    }
}