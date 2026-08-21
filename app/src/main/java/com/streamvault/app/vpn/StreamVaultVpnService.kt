package com.streamvault.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.streamvault.app.MainActivity
import com.streamvault.app.R
import com.streamvault.domain.vpn.VpnConfig
import com.streamvault.domain.vpn.VpnProtocol
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel

class StreamVaultVpnService : VpnService() {

    companion object {
        private const val TAG = "StreamVaultVpn"
        private const val NOTIFICATION_CHANNEL_ID = "streamvault_vpn_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START_VPN = "com.streamvault.app.vpn.START"
        const val ACTION_STOP_VPN = "com.streamvault.app.vpn.STOP"

        const val EXTRA_PROFILE_NAME = "extra_profile_name"
        const val EXTRA_ADDRESS = "extra_address"
        const val EXTRA_DNS = "extra_dns"
        const val EXTRA_MTU = "extra_mtu"
        const val EXTRA_ENDPOINT = "extra_endpoint"
        const val EXTRA_PORT = "extra_port"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_VPN -> {
                val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME) ?: "StreamVault VPN"
                val address = intent.getStringExtra(EXTRA_ADDRESS) ?: "10.0.0.2/32"
                val dns = intent.getStringExtra(EXTRA_DNS) ?: "1.1.1.1"
                val mtu = intent.getIntExtra(EXTRA_MTU, 1420)
                val endpoint = intent.getStringExtra(EXTRA_ENDPOINT) ?: ""
                val port = intent.getIntExtra(EXTRA_PORT, 51820)
                startVpn(profileName, address, dns, mtu, endpoint, port)
            }
            ACTION_STOP_VPN -> {
                stopVpn()
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(
        profileName: String,
        address: String,
        dns: String,
        mtu: Int,
        endpoint: String,
        port: Int
    ) {
        stopVpn()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(profileName))

        try {
            val builder = Builder()
                .setSession("StreamVault Secure Tunnel ($profileName)")
                .setMtu(mtu)

            // IP Address configuration
            val ipPart = address.substringBefore('/')
            val prefixPart = address.substringAfter('/', "32").toIntOrNull() ?: 32
            builder.addAddress(ipPart, prefixPart)

            // DNS configuration
            if (dns.isNotBlank()) {
                dns.split(",", " ").filter { it.isNotBlank() }.forEach { dnsIp ->
                    try {
                        builder.addDnsServer(dnsIp.trim())
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed adding DNS server $dnsIp", e)
                    }
                }
            }

            // Default Route 0.0.0.0/0
            builder.addRoute("0.0.0.0", 0)

            // Split Tunneling: Restrict VPN only to StreamVault!
            try {
                builder.addAllowedApplication(packageName)
                Log.i(TAG, "Applied per-app split tunneling for package: $packageName")
            } catch (e: Exception) {
                Log.w(TAG, "Could not restrict VPN to package: $packageName", e)
            }

            val pfd = builder.establish()
            if (pfd == null) {
                Log.e(TAG, "Failed to establish VPN interface (pfd is null)")
                stopSelf()
                return
            }
            vpnInterface = pfd
            Log.i(TAG, "VPN tunnel interface established successfully for $profileName")

            serviceJob = serviceScope.launch {
                runTunnelLoop(pfd, endpoint, port)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN tunnel", e)
            stopVpn()
        }
    }

    private suspend fun runTunnelLoop(pfd: ParcelFileDescriptor, endpoint: String, port: Int) {
        withContext(Dispatchers.IO) {
            try {
                val input = FileInputStream(pfd.fileDescriptor)
                val output = FileOutputStream(pfd.fileDescriptor)
                val buffer = ByteArray(32768)

                while (isActive) {
                    val length = input.read(buffer)
                    if (length > 0) {
                        // Tunnel traffic loop placeholder
                    } else if (length < 0) {
                        break
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Tunnel packet loop terminated", e)
                }
            }
        }
    }

    private fun stopVpn() {
        serviceJob?.cancel()
        serviceJob = null
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN interface", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "StreamVault VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of active StreamVault VPN connection"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(profileName: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("StreamVault VPN Active")
            .setContentText("Connected to $profileName (Split Tunnel Active)")
            .setSmallIcon(R.mipmap.ic_launcher_vault)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        serviceScope.cancel()
    }
}
