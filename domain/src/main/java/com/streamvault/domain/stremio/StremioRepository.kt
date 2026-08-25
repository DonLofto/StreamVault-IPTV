package com.streamvault.domain.stremio

import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface StremioRepository {
    fun getInstalledAddons(): Flow<List<StremioManifest>>
    suspend fun installAddon(manifestUrl: String): Result<StremioManifest>
    suspend fun uninstallAddon(addonId: String): Result<Unit>
    suspend fun getStreamCandidates(type: String, id: String): Result<List<StremioStream>>
}
