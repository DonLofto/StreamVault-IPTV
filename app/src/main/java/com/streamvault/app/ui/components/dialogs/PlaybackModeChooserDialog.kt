package com.streamvault.app.ui.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import com.streamvault.app.R
import com.streamvault.app.navigation.PlayerNavigationRequest
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated

@Composable
fun PlaybackModeChooserDialog(
    request: PlayerNavigationRequest,
    onPlayInternal: () -> Unit,
    onPlayExternal: () -> Unit,
    onDismiss: () -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.dialog_choose_player_title),
        subtitle = request.title.ifBlank { stringResource(R.string.dialog_choose_player_subtitle) },
        onDismissRequest = onDismiss,
        widthFraction = 0.5f,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TvClickableSurface(
                    onClick = {
                        onDismiss()
                        onPlayInternal()
                    },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = SurfaceElevated,
                        focusedContainerColor = Primary.copy(alpha = 0.28f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_external_playback_mode_internal),
                            style = MaterialTheme.typography.titleSmall,
                            color = OnBackground
                        )
                    }
                }

                TvClickableSurface(
                    onClick = {
                        onDismiss()
                        onPlayExternal()
                    },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = SurfaceElevated,
                        focusedContainerColor = Primary.copy(alpha = 0.28f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_external_playback_mode_external),
                            style = MaterialTheme.typography.titleSmall,
                            color = OnBackground
                        )
                    }
                }
            }
        }
    )
}
