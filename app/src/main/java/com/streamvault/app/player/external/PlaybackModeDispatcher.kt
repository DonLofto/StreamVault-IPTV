package com.streamvault.app.player.external

import android.content.Context
import android.widget.Toast
import com.streamvault.app.R
import com.streamvault.app.navigation.PlayerNavigationRequest
import com.streamvault.domain.model.ExternalPlaybackMode

object PlaybackModeDispatcher {

    sealed interface DispatchResult {
        object PlayInternal : DispatchResult
        object ShowChooser : DispatchResult
        object LaunchExternal : DispatchResult
    }

    fun dispatch(
        mode: ExternalPlaybackMode,
        request: PlayerNavigationRequest,
        forceInternal: Boolean = false
    ): DispatchResult {
        if (forceInternal || mode == ExternalPlaybackMode.INTERNAL_PLAYER) {
            return DispatchResult.PlayInternal
        }
        if (mode == ExternalPlaybackMode.ASK_EVERY_TIME) {
            return DispatchResult.ShowChooser
        }
        return DispatchResult.LaunchExternal
    }

    fun launchExternal(
        context: Context,
        url: String,
        showFeedback: Boolean = true
    ): ExternalPlayerLaunchResult {
        val result = ExternalPlayerLauncher.launch(context, url)
        if (showFeedback) {
            when (result) {
                is ExternalPlayerLaunchResult.Success -> Unit
                is ExternalPlayerLaunchResult.NoHandler ->
                    Toast.makeText(context, context.getString(R.string.player_no_external_player), Toast.LENGTH_SHORT).show()
                is ExternalPlayerLaunchResult.InvalidUrl ->
                    Toast.makeText(context, context.getString(R.string.player_unsafe_stream_url), Toast.LENGTH_SHORT).show()
                is ExternalPlayerLaunchResult.Failed ->
                    Toast.makeText(context, context.getString(R.string.player_external_launch_failed), Toast.LENGTH_SHORT).show()
            }
        }
        return result
    }
}
