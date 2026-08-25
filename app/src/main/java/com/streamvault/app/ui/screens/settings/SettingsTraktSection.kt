package com.streamvault.app.ui.screens.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.theme.SurfaceHighlight
import com.streamvault.domain.trakt.TraktAuthState

internal fun LazyListScope.settingsTraktSection(
    traktState: TraktAuthState,
    onStartPairing: () -> Unit,
    onCancelPairing: () -> Unit,
    onDisconnect: () -> Unit
) {
    item {
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
                            text = "Trakt.tv Scrobbling & Sync",
                            style = MaterialTheme.typography.titleMedium,
                            color = OnSurface
                        )
                        Text(
                            text = "Automatically scrobble movie and TV show playback progress and sync watch history.",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnBackground.copy(alpha = 0.7f)
                        )
                    }
                    if (traktState.isAuthenticated) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            colors = SurfaceDefaults.colors(containerColor = Color(0xFF4CAF50).copy(alpha = 0.2f))
                        ) {
                            Text(
                                text = "Connected",
                                color = Color(0xFF4CAF50),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = SurfaceHighlight.copy(alpha = 0.4f))

                when {
                    traktState.isAuthenticated -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Account",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = OnBackground.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = "@${traktState.username ?: "Trakt User"}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = OnSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            TvButton(
                                onClick = onDisconnect,
                                colors = ButtonDefaults.colors(
                                    containerColor = Color(0xFFE53935),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Disconnect")
                            }
                        }
                    }

                    traktState.isPendingAuthorization -> {
                        val qrBitmap = remember(traktState.verificationUrl, traktState.userCode) {
                            val url = traktState.verificationUrl ?: "https://trakt.tv/activate"
                            generateTraktQrCode(url)
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (qrBitmap != null) {
                                Box(
                                    modifier = Modifier
                                        .size(180.dp)
                                        .background(Color.White, RoundedCornerShape(12.dp))
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = qrBitmap.asImageBitmap(),
                                        contentDescription = "Trakt Activation QR Code",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            Text(
                                text = "1. Scan the QR code or visit https://trakt.tv/activate",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurface
                            )

                            Text(
                                text = "2. Enter authorization code:",
                                style = MaterialTheme.typography.bodySmall,
                                color = OnBackground.copy(alpha = 0.7f)
                            )

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                colors = SurfaceDefaults.colors(containerColor = Primary.copy(alpha = 0.2f)),
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = traktState.userCode ?: "----",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 4.sp,
                                    color = Primary,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Primary,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Waiting for authorization...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnBackground.copy(alpha = 0.7f)
                                )
                            }

                            TvButton(
                                onClick = onCancelPairing,
                                colors = ButtonDefaults.colors(
                                    containerColor = SurfaceHighlight,
                                    contentColor = OnSurface
                                )
                            ) {
                                Text("Cancel Pairing")
                            }
                        }
                    }

                    else -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Connect your Trakt.tv account to track what you watch across devices.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnBackground.copy(alpha = 0.8f)
                            )
                            Spacer(Modifier.height(4.dp))
                            TvButton(
                                onClick = onStartPairing,
                                colors = ButtonDefaults.colors(
                                    containerColor = Color(0xFFED1C24),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Connect Trakt.tv Account")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun generateTraktQrCode(url: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        for (x in 0 until 512) {
            for (y in 0 until 512) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
