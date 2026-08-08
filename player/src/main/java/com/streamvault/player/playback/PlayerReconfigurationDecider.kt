package com.streamvault.player.playback

import com.streamvault.domain.model.DecoderMode

internal data class PlayerReconfigurationInput(
    val activeAudioDecoderMode: DecoderMode,
    val preferredAudioDecoderMode: DecoderMode,
    val activeVideoDecoderMode: DecoderMode,
    val preferredVideoDecoderMode: DecoderMode,
    val previousAudioDecoderPolicy: ActiveDecoderPolicy,
    val nextAudioDecoderPolicy: ActiveDecoderPolicy,
    val previousVideoDecoderPolicy: ActiveDecoderPolicy,
    val nextVideoDecoderPolicy: ActiveDecoderPolicy,
    val isLiveBuffer: Boolean,
    val currentBufferIsLive: Boolean,
    val requestedAudioDecoderMode: DecoderMode,
    val requestedVideoDecoderMode: DecoderMode
)

/**
 * Decides whether a full player re-creation is required for a config change.
 *
 * Buffer-policy changes (e.g. live HLS buffer promotion from `stable-live` to `large-live`) are
 * deliberately NOT part of this decision: they must be applied to the live [androidx.media3
 * .exoplayer.ExoPlayer] in place via [PolicyAwareLoadControl.updatePolicy], never via a teardown.
 */
internal object PlayerReconfigurationDecider {

    fun requiresPlayerRecreation(input: PlayerReconfigurationInput): Boolean =
        input.activeAudioDecoderMode != input.preferredAudioDecoderMode ||
            input.activeVideoDecoderMode != input.preferredVideoDecoderMode ||
            input.previousAudioDecoderPolicy != input.nextAudioDecoderPolicy ||
            input.previousVideoDecoderPolicy != input.nextVideoDecoderPolicy ||
            input.isLiveBuffer != input.currentBufferIsLive ||
            input.requestedAudioDecoderMode == DecoderMode.COMPATIBILITY ||
            input.requestedVideoDecoderMode == DecoderMode.COMPATIBILITY
}