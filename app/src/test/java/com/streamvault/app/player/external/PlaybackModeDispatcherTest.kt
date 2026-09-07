package com.streamvault.app.player.external

import com.streamvault.app.navigation.PlayerNavigationRequest
import com.streamvault.domain.model.ExternalPlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackModeDispatcherTest {

    private val sampleRequest = PlayerNavigationRequest(
        streamUrl = "https://stream.example.com/live.m3u8",
        title = "Test Channel"
    )

    @Test
    fun `internal mode dispatches PlayInternal`() {
        val result = PlaybackModeDispatcher.dispatch(
            mode = ExternalPlaybackMode.INTERNAL_PLAYER,
            request = sampleRequest
        )
        assertEquals(PlaybackModeDispatcher.DispatchResult.PlayInternal, result)
    }

    @Test
    fun `ask every time dispatches ShowChooser`() {
        val result = PlaybackModeDispatcher.dispatch(
            mode = ExternalPlaybackMode.ASK_EVERY_TIME,
            request = sampleRequest
        )
        assertEquals(PlaybackModeDispatcher.DispatchResult.ShowChooser, result)
    }

    @Test
    fun `external mode dispatches LaunchExternal`() {
        val result = PlaybackModeDispatcher.dispatch(
            mode = ExternalPlaybackMode.EXTERNAL_PLAYER,
            request = sampleRequest
        )
        assertEquals(PlaybackModeDispatcher.DispatchResult.LaunchExternal, result)
    }

    @Test
    fun `forceInternal overrides external and ask modes`() {
        val resultExternal = PlaybackModeDispatcher.dispatch(
            mode = ExternalPlaybackMode.EXTERNAL_PLAYER,
            request = sampleRequest,
            forceInternal = true
        )
        assertEquals(PlaybackModeDispatcher.DispatchResult.PlayInternal, resultExternal)

        val resultAsk = PlaybackModeDispatcher.dispatch(
            mode = ExternalPlaybackMode.ASK_EVERY_TIME,
            request = sampleRequest,
            forceInternal = true
        )
        assertEquals(PlaybackModeDispatcher.DispatchResult.PlayInternal, resultAsk)
    }
}
