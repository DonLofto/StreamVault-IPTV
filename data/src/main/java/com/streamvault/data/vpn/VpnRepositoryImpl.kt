package com.streamvault.data.vpn

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamvault.domain.model.Result
import com.streamvault.domain.vpn.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.vpnDataStore by preferencesDataStore(name = "streamvault_vpn_preferences")

@Singleton
class VpnRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) : VpnRepository {

    private companion object {
        const val ACTION_START_VPN = "com.streamvault.app.vpn.START"
        const val ACTION_STOP_VPN = "com.streamvault.app.vpn.STOP"
        const val EXTRA_PROFILE_NAME = "extra_profile_name"
        const val EXTRA_ADDRESS = "extra_address"
        const val EXTRA_DNS = "extra_dns"
        const val EXTRA_MTU = "extra_mtu"
        const val EXTRA_ENDPOINT = "extra_endpoint"
        const val EXTRA_PORT = "extra_port"

        val KEY_PROFILES_JSON = stringPreferencesKey("vpn_profiles_json")
    }

    private val _status = MutableStateFlow(VpnStatus())
    override val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val profilesType = object : TypeToken<List<VpnConfig>>() {}.type
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        scope.launch {
            val active = getActiveProfile()
            if (active != null && active.isEnabled) {
                _status.value = VpnStatus(
                    isConnected = true,
                    activeProfileName = active.name
                )
            }
        }
    }

    override fun getProfiles(): Flow<List<VpnConfig>> {
        return context.vpnDataStore.data.map { prefs ->
            val json = prefs[KEY_PROFILES_JSON]
            if (json.isNullOrBlank()) {
                emptyList()
            } else {
                try {
                    gson.fromJson<List<VpnConfig>>(json, profilesType) ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
    }

    override suspend fun getActiveProfile(): VpnConfig? = withContext(Dispatchers.IO) {
        val profiles = getProfiles().first()
        profiles.firstOrNull { it.isEnabled }
    }

    override suspend fun saveProfile(config: VpnConfig): Long = withContext(Dispatchers.IO) {
        val profiles = getProfiles().first().toMutableList()
        val id = if (config.id <= 0) System.currentTimeMillis() else config.id
        val newConfig = config.copy(id = id)

        val existingIndex = profiles.indexOfFirst { it.id == id }
        if (existingIndex >= 0) {
            profiles[existingIndex] = newConfig
        } else {
            profiles.add(newConfig)
        }

        context.vpnDataStore.edit { prefs ->
            prefs[KEY_PROFILES_JSON] = gson.toJson(profiles)
        }
        id
    }

    override suspend fun deleteProfile(id: Long): Unit = withContext(Dispatchers.IO) {
        val profiles = getProfiles().first().toMutableList()
        val toDelete = profiles.firstOrNull { it.id == id }
        if (toDelete != null && toDelete.isEnabled) {
            setActiveProfile(id, false)
        }
        profiles.removeAll { it.id == id }
        context.vpnDataStore.edit { prefs ->
            prefs[KEY_PROFILES_JSON] = gson.toJson(profiles)
        }
        Unit
    }

    override suspend fun setActiveProfile(id: Long, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        val profiles = getProfiles().first().toMutableList()
        val targetIndex = profiles.indexOfFirst { it.id == id }
        if (targetIndex >= 0) {
            val target = profiles[targetIndex]
            if (enabled) {
                // Disable any other enabled profile
                for (i in profiles.indices) {
                    profiles[i] = profiles[i].copy(isEnabled = profiles[i].id == id)
                }
                startVpnService(target)
                _status.value = VpnStatus(
                    isConnected = true,
                    activeProfileName = target.name
                )
            } else {
                profiles[targetIndex] = target.copy(isEnabled = false)
                stopVpnService()
                _status.value = VpnStatus(
                    isConnected = false,
                    activeProfileName = null
                )
            }
            context.vpnDataStore.edit { prefs ->
                prefs[KEY_PROFILES_JSON] = gson.toJson(profiles)
            }
        }
        Unit
    }

    override fun parseWireGuardConfig(rawConfig: String, profileName: String): Result<VpnConfig> {
        return WireGuardConfigParser.parse(rawConfig, profileName)
    }

    private fun startVpnService(config: VpnConfig) {
        try {
            val intent = Intent().apply {
                setClassName(context.packageName, "com.streamvault.app.vpn.StreamVaultVpnService")
                action = ACTION_START_VPN
                putExtra(EXTRA_PROFILE_NAME, config.name)
                putExtra(EXTRA_ADDRESS, config.address)
                putExtra(EXTRA_DNS, config.dns)
                putExtra(EXTRA_MTU, config.mtu)
                putExtra(EXTRA_ENDPOINT, config.endpoint)
                putExtra(EXTRA_PORT, config.port)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            _status.value = VpnStatus(
                isConnected = false,
                errorMessage = e.message
            )
        }
    }

    private fun stopVpnService() {
        try {
            val intent = Intent().apply {
                setClassName(context.packageName, "com.streamvault.app.vpn.StreamVaultVpnService")
                action = ACTION_STOP_VPN
            }
            context.startService(intent)
        } catch (e: Exception) {
            // Ignore
        }
    }
}
