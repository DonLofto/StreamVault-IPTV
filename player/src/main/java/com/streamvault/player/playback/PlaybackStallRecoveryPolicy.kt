package com.streamvault.player.playback

import com.streamvault.player.PlaybackState

internal fun shouldRecoverReadyStalls(resolvedStreamType: ResolvedStreamType): Boolean =
    true

internal fun shouldRecoverPositionAdvancingReadyStalls(resolvedStreamType: ResolvedStreamType): Boolean =
    !resolvedStreamType.isLiveForStallRecovery

internal fun shouldRecoverFrameSilentReadyStalls(resolvedStreamType: ResolvedStreamType): Boolean =
    resolvedStreamType.isLiveForStallRecovery

internal fun shouldReconnectLiveStall(
    playbackState: PlaybackState,
    resolvedStreamType: ResolvedStreamType,
    recoveryAttempt: Int,
    bufferedDurationMs: Long = 0L
): Boolean =
    recoveryAttempt >= 2 &&
        resolvedStreamType.isLiveForStallRecovery &&
        (
            playbackState == PlaybackState.BUFFERING ||
                playbackState == PlaybackState.READY && bufferedDurationMs < STALL_RECONNECT_MIN_READY_BUFFER_MS
        )

private const val STALL_RECONNECT_MIN_READY_BUFFER_MS = 3_000L

private val ResolvedStreamType.isLiveForStallRecovery: Boolean
    get() = this == ResolvedStreamType.HLS ||
        this == ResolvedStreamType.SMOOTH_STREAMING ||
        this == ResolvedStreamType.MPEG_TS_LIVE ||
        this == ResolvedStreamType.RTSP
