package com.streamvault.domain.vpn

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class VpnProtocol {
    WIREGUARD,
    OPENVPN
}

data class VpnConfig(
    val id: Long = 0,
    val name: String,
    val protocol: VpnProtocol = VpnProtocol.WIREGUARD,
    val privateKey: String = "",
    val publicKey: String = "",
    val presharedKey: String = "",
    val endpoint: String = "",
    val port: Int = 51820,
    val address: String = "10.0.0.2/32",
    val dns: String = "1.1.1.1",
    val mtu: Int = 1420,
    val isEnabled: Boolean = false,
    val rawConfig: String = ""
)

data class VpnStatus(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val activeProfileName: String? = null,
    val bytesReceived: Long = 0,
    val bytesSent: Long = 0,
    val errorMessage: String? = null
)

interface VpnRepository {
    val status: StateFlow<VpnStatus>
    fun getProfiles(): Flow<List<VpnConfig>>
    suspend fun getActiveProfile(): VpnConfig?
    suspend fun saveProfile(config: VpnConfig): Long
    suspend fun deleteProfile(id: Long)
    suspend fun setActiveProfile(id: Long, enabled: Boolean)
    fun parseWireGuardConfig(rawConfig: String, profileName: String = "WireGuard"): com.streamvault.domain.model.Result<VpnConfig>
}
