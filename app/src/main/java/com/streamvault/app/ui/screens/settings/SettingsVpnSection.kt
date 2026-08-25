package com.streamvault.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
import com.streamvault.domain.vpn.VpnConfig
import com.streamvault.domain.vpn.VpnStatus

internal fun LazyListScope.settingsVpnSection(
    vpnStatus: VpnStatus,
    vpnProfiles: List<VpnConfig>,
    onToggleVpn: (Long, Boolean) -> Unit,
    onImportConfig: (String, String, (Boolean, String?) -> Unit) -> Unit,
    onDeleteProfile: (Long) -> Unit
) {
    item {
        var showImportDialog by remember { mutableStateOf(false) }

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
                            text = "Built-in WireGuard VPN Client",
                            style = MaterialTheme.typography.titleMedium,
                            color = OnSurface
                        )
                        Text(
                            text = "Secure your IPTV traffic with split-tunneling WireGuard protection.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnBackground.copy(alpha = 0.7f)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        colors = SurfaceDefaults.colors(
                            containerColor = if (vpnStatus.isConnected) Color(0xFF4CAF50).copy(alpha = 0.2f)
                            else Color(0xFFE53935).copy(alpha = 0.2f)
                        )
                    ) {
                        Text(
                            text = if (vpnStatus.isConnected) "Connected" else "Disconnected",
                            color = if (vpnStatus.isConnected) Color(0xFF4CAF50) else Color(0xFFE53935),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                HorizontalDivider(color = SurfaceHighlight.copy(alpha = 0.4f))

                if (vpnStatus.errorMessage != null) {
                    Text(
                        text = "Error: ${vpnStatus.errorMessage}",
                        color = Color(0xFFE53935),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Import and manage WireGuard .conf profiles",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnBackground.copy(alpha = 0.8f)
                    )
                    TvButton(
                        onClick = { showImportDialog = true },
                        colors = ButtonDefaults.colors(
                            containerColor = Primary,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Import .conf Profile")
                    }
                }

                if (showImportDialog) {
                    ImportWireGuardDialog(
                        onDismiss = { showImportDialog = false },
                        onImport = { name, rawText, onResult ->
                            onImportConfig(name, rawText, onResult)
                        }
                    )
                }
            }
        }
    }

    if (vpnProfiles.isEmpty()) {
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
                        text = "No VPN profiles imported. Click 'Import .conf Profile' to add a WireGuard config.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnBackground.copy(alpha = 0.6f)
                    )
                }
            }
        }
    } else {
        items(vpnProfiles, key = { it.id }) { profile ->
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
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = OnSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Endpoint: ${profile.endpoint}:${profile.port}  •  IP: ${profile.address}",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnBackground.copy(alpha = 0.6f)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Switch(
                            checked = profile.isEnabled && vpnStatus.isConnected,
                            onCheckedChange = { isChecked ->
                                onToggleVpn(profile.id, isChecked)
                            }
                        )
                        TvButton(
                            onClick = { onDeleteProfile(profile.id) },
                            colors = ButtonDefaults.colors(
                                containerColor = SurfaceHighlight,
                                contentColor = Color(0xFFEF5350)
                            )
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportWireGuardDialog(
    onDismiss: () -> Unit,
    onImport: (String, String, (Boolean, String?) -> Unit) -> Unit
) {
    var profileName by remember { mutableStateOf("WireGuard") }
    var configText by remember { mutableStateOf("") }
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
                    text = "Import WireGuard Configuration",
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface
                )

                Text(
                    text = "Profile Name:",
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
                        value = profileName,
                        onValueChange = { profileName = it },
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        cursorBrush = SolidColor(Primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(
                    text = "Paste .conf Content:",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnBackground.copy(alpha = 0.7f)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    BasicTextField(
                        value = configText,
                        onValueChange = { configText = it },
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(Primary),
                        modifier = Modifier.fillMaxSize()
                    )
                    if (configText.isBlank()) {
                        Text(
                            text = "[Interface]\nPrivateKey = ...\nAddress = 10.0.0.2/32\n\n[Peer]\nPublicKey = ...\nEndpoint = vpn.example.com:51820",
                            style = TextStyle(
                                color = Color.White.copy(alpha = 0.3f),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
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
                    TvButton(
                        onClick = {
                            isSubmitting = true
                            errorMessage = null
                            onImport(profileName, configText) { success, err ->
                                isSubmitting = false
                                if (success) {
                                    onDismiss()
                                } else {
                                    errorMessage = err ?: "Failed to parse WireGuard config"
                                }
                            }
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = Primary,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Import & Save")
                    }
                }
            }
        }
    }
}
