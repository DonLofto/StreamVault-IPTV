package com.streamvault.data.vpn

import com.streamvault.domain.model.Result
import com.streamvault.domain.vpn.VpnConfig
import com.streamvault.domain.vpn.VpnProtocol

object WireGuardConfigParser {

    fun parse(rawText: String, profileName: String = "WireGuard"): Result<VpnConfig> {
        try {
            var privateKey = ""
            var address = "10.0.0.2/32"
            var dns = "1.1.1.1"
            var mtu = 1420
            var publicKey = ""
            var presharedKey = ""
            var endpoint = ""
            var port = 51820

            var currentSection = ""

            for (line in rawText.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#") || trimmed.isBlank()) continue

                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    currentSection = trimmed.substring(1, trimmed.length - 1).trim().lowercase()
                    continue
                }

                val equalsIndex = trimmed.indexOf('=')
                if (equalsIndex == -1) continue

                val key = trimmed.substring(0, equalsIndex).trim().lowercase()
                val value = trimmed.substring(equalsIndex + 1).trim()

                when (currentSection) {
                    "interface" -> when (key) {
                        "privatekey" -> privateKey = value
                        "address" -> address = value
                        "dns" -> dns = value
                        "mtu" -> mtu = value.toIntOrNull() ?: 1420
                    }
                    "peer" -> when (key) {
                        "publickey" -> publicKey = value
                        "presharedkey" -> presharedKey = value
                        "endpoint" -> {
                            val lastColon = value.lastIndexOf(':')
                            if (lastColon != -1) {
                                endpoint = value.substring(0, lastColon).trim()
                                port = value.substring(lastColon + 1).toIntOrNull() ?: 51820
                            } else {
                                endpoint = value
                            }
                        }
                    }
                }
            }

            if (privateKey.isBlank()) {
                return Result.error("Invalid WireGuard configuration: Missing PrivateKey in [Interface]")
            }
            if (publicKey.isBlank()) {
                return Result.error("Invalid WireGuard configuration: Missing PublicKey in [Peer]")
            }
            if (endpoint.isBlank()) {
                return Result.error("Invalid WireGuard configuration: Missing Endpoint in [Peer]")
            }

            val config = VpnConfig(
                name = profileName,
                protocol = VpnProtocol.WIREGUARD,
                privateKey = privateKey,
                publicKey = publicKey,
                presharedKey = presharedKey,
                endpoint = endpoint,
                port = port,
                address = address,
                dns = dns,
                mtu = mtu,
                rawConfig = rawText
            )
            return Result.success(config)
        } catch (e: Exception) {
            return Result.error("Failed to parse WireGuard config: ${e.message}", e)
        }
    }
}
