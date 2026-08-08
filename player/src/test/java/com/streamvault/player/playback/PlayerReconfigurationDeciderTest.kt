package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.DecoderMode
import org.junit.Test

class PlayerReconfigurationDeciderTest {

    private fun config(
        activeAudio: DecoderMode = DecoderMode.AUTO,
        preferredAudio: DecoderMode = DecoderMode.AUTO,
        activeVideo: DecoderMode = DecoderMode.AUTO,
        preferredVideo: DecoderMode = DecoderMode.AUTO,
        previousAudioPolicy: ActiveDecoderPolicy = ActiveDecoderPolicy.AUTO,
        nextAudioPolicy: ActiveDecoderPolicy = ActiveDecoderPolicy.AUTO,
        previousVideoPolicy: ActiveDecoderPolicy = ActiveDecoderPolicy.AUTO,
        nextVideoPolicy: ActiveDecoderPolicy = ActiveDecoderPolicy.AUTO,
        isLiveBuffer: Boolean = true,
        currentBufferIsLive: Boolean = true,
        requestedAudio: DecoderMode = DecoderMode.AUTO,
        requestedVideo: DecoderMode = DecoderMode.AUTO
    ) = PlayerReconfigurationInput(
        activeAudioDecoderMode = activeAudio,
        preferredAudioDecoderMode = preferredAudio,
        activeVideoDecoderMode = activeVideo,
        preferredVideoDecoderMode = preferredVideo,
        previousAudioDecoderPolicy = previousAudioPolicy,
        nextAudioDecoderPolicy = nextAudioPolicy,
        previousVideoDecoderPolicy = previousVideoPolicy,
        nextVideoDecoderPolicy = nextVideoPolicy,
        isLiveBuffer = isLiveBuffer,
        currentBufferIsLive = currentBufferIsLive,
        requestedAudioDecoderMode = requestedAudio,
        requestedVideoDecoderMode = requestedVideo
    )

    @Test
    fun `identical configuration does not require recreation`() {
        assertThat(PlayerReconfigurationDecider.requiresPlayerRecreation(config())).isFalse()
    }

    @Test
    fun `buffer label change alone does not require recreation`() {
        // The decider has no buffer-label input: a policy-label-only change (e.g. stable-live ->
        // large-live promotion) must not drive a decoder-mode rebuild.
        assertThat(PlayerReconfigurationDecider.requiresPlayerRecreation(config())).isFalse()
    }

    @Test
    fun `audio decoder mode change requires recreation`() {
        val input = config(activeAudio = DecoderMode.HARDWARE, preferredAudio = DecoderMode.SOFTWARE)
        assertThat(PlayerReconfigurationDecider.requiresPlayerRecreation(input)).isTrue()
    }

    @Test
    fun `video decoder mode change requires recreation`() {
        val input = config(activeVideo = DecoderMode.SOFTWARE, preferredVideo = DecoderMode.HARDWARE)
        assertThat(PlayerReconfigurationDecider.requiresPlayerRecreation(input)).isTrue()
    }

    @Test
    fun `audio decoder policy change requires recreation`() {
        val input = config(
            previousAudioPolicy = ActiveDecoderPolicy.AUTO,
            nextAudioPolicy = ActiveDecoderPolicy.HARDWARE_PREFERRED
        )
        assertThat(PlayerReconfigurationDecider.requiresPlayerRecreation(input)).isTrue()
    }

    @Test
    fun `video decoder policy change requires recreation`() {
        val input = config(
            previousVideoPolicy = ActiveDecoderPolicy.AUTO,
            nextVideoPolicy = ActiveDecoderPolicy.COMPATIBILITY
        )
        assertThat(PlayerReconfigurationDecider.requiresPlayerRecreation(input)).isTrue()
    }

    @Test
    fun `live to non-live buffer type change requires recreation`() {
        val input = config(isLiveBuffer = false, currentBufferIsLive = true)
        assertThat(PlayerReconfigurationDecider.requiresPlayerRecreation(input)).isTrue()
    }

    @Test
    fun `requested compatibility audio mode requires recreation`() {
        val input = config(requestedAudio = DecoderMode.COMPATIBILITY)
        assertThat(PlayerReconfigurationDecider.requiresPlayerRecreation(input)).isTrue()
    }

    @Test
    fun `requested compatibility video mode requires recreation`() {
        val input = config(requestedVideo = DecoderMode.COMPATIBILITY)
        assertThat(PlayerReconfigurationDecider.requiresPlayerRecreation(input)).isTrue()
    }
}