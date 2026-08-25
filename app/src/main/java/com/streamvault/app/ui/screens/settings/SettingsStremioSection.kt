package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.theme.SurfaceHighlight
import com.streamvault.domain.stremio.StremioManifest

internal fun LazyListScope.settingsStremioSection(
    addons: List<StremioManifest>,
    onInstallAddon: (String, (Boolean, String?) -> Unit) -> Unit,
    onUninstallAddon: (String) -> Unit
) {
    item {
        var showInstallDialog by remember { mutableStateOf(false) }

        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Stremio Add-on Protocol v3 Engine",
                            style = MaterialTheme.typography.titleMedium,
                            color = OnSurface
                        )
                        Text(
                            text = "Connect community and streaming add-ons using the standard Stremio manifest protocol.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnBackground.copy(alpha = 0.7f)
                        )
                    }

                    TvButton(
                        onClick = { showInstallDialog = true },
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xFF8E24AA),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Install Add-on")
                    }
                }

                HorizontalDivider(color = SurfaceHighlight.copy(alpha = 0.4f))

                Text(
                    text = "Installed Add-ons (${addons.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = OnSurface
                )

                if (showInstallDialog) {
                    InstallStremioAddonDialog(
                        onDismiss = { showInstallDialog = false },
                        onInstall = onInstallAddon
                    )
                }
            }
        }
    }

    if (addons.isEmpty()) {
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                colors = SurfaceDefaults.colors(containerColor = SurfaceElevated.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No Stremio add-ons installed. Click 'Install Add-on' and enter a manifest URL.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnBackground.copy(alpha = 0.6f)
                    )
                }
            }
        }
    } else {
        items(addons, key = { it.id }) { addon ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = addon.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = OnSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                colors = SurfaceDefaults.colors(containerColor = SurfaceHighlight)
                            ) {
                                Text(
                                    text = "v${addon.version}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OnBackground.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        val desc = addon.description
                        if (!desc.isNullOrBlank()) {
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = OnBackground.copy(alpha = 0.7f),
                                maxLines = 2
                            )
                        }
                        if (addon.types.isNotEmpty()) {
                            Text(
                                text = "Types: ${addon.types.joinToString(", ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Primary.copy(alpha = 0.8f)
                            )
                        }
                    }

                    TvButton(
                        onClick = { onUninstallAddon(addon.id) },
                        colors = ButtonDefaults.colors(
                            containerColor = SurfaceHighlight,
                            contentColor = Color(0xFFEF5350)
                        )
                    ) {
                        Text("Uninstall")
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallStremioAddonDialog(
    onDismiss: () -> Unit,
    onInstall: (String, (Boolean, String?) -> Unit) -> Unit
) {
    var manifestUrl by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = SurfaceElevated),
            modifier = Modifier
                .width(540.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Install Stremio Add-on",
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface
                )

                Text(
                    text = "Manifest URL (e.g. https://.../manifest.json):",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnBackground.copy(alpha = 0.7f)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    BasicTextField(
                        value = manifestUrl,
                        onValueChange = { manifestUrl = it },
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        cursorBrush = SolidColor(Primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (manifestUrl.isBlank()) {
                        Text(
                            text = "https://torrentio.strem.fun/manifest.json",
                            style = TextStyle(color = Color.White.copy(alpha = 0.3f), fontSize = 14.sp)
                        )
                    }
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = Color(0xFFEF5350),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.colors(
                            containerColor = SurfaceHighlight,
                            contentColor = OnSurface
                        )
                    ) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(10.dp))
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Primary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        TvButton(
                            onClick = {
                                isSubmitting = true
                                errorMessage = null
                                onInstall(manifestUrl) { success, err ->
                                    isSubmitting = false
                                    if (success) {
                                        onDismiss()
                                    } else {
                                        errorMessage = err ?: "Failed to install Stremio add-on"
                                    }
                                }
                            },
                            colors = ButtonDefaults.colors(
                                containerColor = Color(0xFF8E24AA),
                                contentColor = Color.White
                            )
                        ) {
                            Text("Install")
                        }
                    }
                }
            }
        }
    }
}
